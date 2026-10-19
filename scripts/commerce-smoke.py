"""Real local Compose acceptance: creates fresh test accounts and assets; never uses live payment.
Runs mock trades, kills/restarts media-worker, republishes a committed CDC event and restarts API.
Requires ffmpeg, Docker CLI, compose.local.yml, Mailpit. Keeps data and writes a secret-free report.
"""
import argparse,hashlib,http.cookiejar,http.client,json,pathlib,re,subprocess,tempfile,time,urllib.request,urllib.error,uuid,datetime
P=argparse.ArgumentParser();P.add_argument('--skip-worker-crash',action='store_true');P.add_argument('--result',default='docs/commerce-smoke-result.json');a=P.parse_args()
COMPOSE=['docker','compose','-f','compose.yml','-f','compose.local.yml'];BASE='http://localhost:28080';ORIGIN='http://localhost:3000'
def docker(*args,input=None):
 r=subprocess.run(COMPOSE+list(args),input=input,text=True,capture_output=True,encoding='utf-8',timeout=180)
 if r.returncode:raise RuntimeError('Docker operation failed: '+r.stderr[-1500:])
 return r.stdout.strip()
def sql(query,db='framehub_video'):
 return docker('exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names '+db,input=query+';\n')
def poll(fn,description,timeout=180,delay=2):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  result=fn()
  if result:return result
  time.sleep(delay)
 raise RuntimeError('Timed out: '+description)
class Client:
 def __init__(self):self.token=None;self.http=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def call(self,method,path,body=None,status=200):
  headers={'Origin':ORIGIN}
  if self.token:headers['Authorization']='Bearer '+self.token
  if body is not None:headers['Content-Type']='application/json'
  req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
  for attempt in range(6):
   try:r=self.http.open(req,timeout=120)
   except urllib.error.HTTPError as e:r=e
   if r.status==429 and attempt<5:r.close();time.sleep(1.1);continue
   raw=r.read();assert r.status==status,(path,r.status,raw.decode()[:500]);assert r.headers.get('Access-Control-Allow-Origin')==ORIGIN,(path,'CORS');return json.loads(raw)['data']
 def register(self,label):
  email='commerce-'+uuid.uuid4().hex+'@example.com';password='LocalTest-'+uuid.uuid4().hex+'!'
  self.call('POST','/auth/email-code',{'email':email,'scene':'REGISTER'})
  def find():
   messages=json.load(urllib.request.urlopen('http://localhost:18025/api/v1/messages'))['messages']
   for m in messages:
    if any(x['Address']==email for x in m['To']):
     detail=json.load(urllib.request.urlopen('http://localhost:18025/api/v1/message/'+m['ID']));return re.search(r'\b[0-9]{6}\b',detail['Text']).group()
  code=poll(find,'verification email',30,.5)
  u=self.call('POST','/auth/register',{'email':email,'password':password,'verificationCode':code,'nickname':label},201)
  self.token=self.call('POST','/auth/login',{'email':email,'password':password})['accessToken'];assert self.call('GET','/users/me')['userId']==u['userId'];return u
 def pay(self,order):
  p=self.call('POST','/orders/'+order+'/pay',{});assert p['provider']=='MOCK','Refusing live payment';assert self.call('POST','/orders/'+order+'/pay',{})['id']==p['id']
  self.call('POST','/dev/payments/'+p['id']+'/succeed',{});self.call('POST','/dev/payments/'+p['id']+'/succeed',{});return p
def ready_gateway():
 try: urllib.request.urlopen(BASE+'/users/me',timeout=2)
 except urllib.error.HTTPError as e: return e.code==401
 except (urllib.error.URLError,http.client.HTTPException,TimeoutError,ConnectionError): return False
 return False
poll(ready_gateway,'Gateway readiness',120)
creator=Client();buyer=Client();report={};original_rule=None
try:
 cu=creator.register('Acceptance creator');bu=buyer.register('Acceptance buyer');creator.call('POST','/creators/me/activate',{})
 # Grant only this newly created local test account. No existing account credentials are changed.
 sql("INSERT INTO user_roles(user_id,role) VALUES("+str(int(cu['userId']))+",'ADMIN')",'framehub')
 original_rule=creator.call('GET','/marketplace/fee-rule');rule={k:v for k,v in original_rule.items() if k!='version'};rule.update(freezeSeconds=2,effectiveAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
 creator.call('POST','/admin/fee-rules',rule)
 with tempfile.TemporaryDirectory(prefix='framehub-acceptance-') as work:
  file=pathlib.Path(work)/'source.mp4';duration='3' if a.skip_worker_crash else '60'
  subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=1920x1080:rate=25','-t',duration,'-c:v','libx264','-preset','ultrafast','-threads','1','-pix_fmt','yuv420p',str(file)],check=True)
  size=file.stat().st_size;source_hash=hashlib.sha256(file.read_bytes()).hexdigest()
  session=creator.call('POST','/api/v1/videos/uploads/init',{'filename':'source.mp4','title':'Full acceptance video','fileSize':size,'contentType':'video/mp4'});path='/api/v1/videos/uploads/'+session['uploadSessionId'];video=str(uuid.UUID(session['videoId']))
  with file.open('rb') as stream:
   for part in range(1,session['partCount']+1):
    chunk=stream.read(session['partSize']);signed=creator.call('POST',path+'/parts/presign',{'partNumber':part,'sizeBytes':len(chunk)})
    with urllib.request.urlopen(urllib.request.Request(signed['uploadUrl'],data=chunk,headers={'Origin':ORIGIN},method='PUT'),timeout=120) as response:assert response.status==200
    if part==1:assert creator.call('GET',path)['uploadedParts'][0]['partNumber']==1
  creator.call('POST',path+'/complete',{});creator.call('POST',path+'/complete',{})
  before=None
  if not a.skip_worker_crash:
   def checkpoint():
    row=sql("SELECT s.artifact_prefix,t.execution_version FROM video_transcode_step s JOIN video_transcode_task t ON t.task_id=s.task_id WHERE t.video_id='"+video+"' AND s.stage_name='TRANSCODE_480P' AND s.status='SUCCEEDED' AND t.status='PROCESSING'")
    return row.split('\t') if row else None
   before=poll(checkpoint,'first completed checkpoint',180,.3)
   docker('kill','-s','SIGKILL','media-worker');docker('up','-d','media-worker')
  def ready():
   v=creator.call('GET','/api/v1/videos/'+video)
   if v['status']=='FAILED':raise RuntimeError('Video failed: '+str(v))
   return v if v['status']=='READY' else None
  v=poll(ready,'Worker recovery and READY',600)
  if before:
   after=sql("SELECT s.artifact_prefix,t.execution_version FROM video_transcode_step s JOIN video_transcode_task t ON t.task_id=s.task_id WHERE t.video_id='"+video+"' AND s.stage_name='TRANSCODE_480P'").split('\t')
   assert after[0]==before[0],('checkpoint not reused',before,after);assert int(after[1])>int(before[1]);report['workerRecovery']={'checkpointReused':True,'oldExecution':int(before[1]),'newExecution':int(after[1])}
  playback=creator.call('GET','/api/v1/videos/'+video+'/playback');subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',playback['masterUrl'],'-t','1','-f','null','-'],check=True)
  material=creator.call('POST','/materials',{'videoId':video,'title':'Licensed acceptance film','description':'Actual local integration asset','category':'nature','tags':['acceptance'],'priceCent':10000,'license':{'commercialUse':True,'scope':'Worldwide, one buyer','durationDays':30,'redistribution':False,'modification':True,'description':'Acceptance license version 1'}});mid=material['id']
  listing=creator.call('POST','/materials/'+mid+'/publish',{'idempotencyKey':uuid.uuid4().hex});assert creator.call('GET','/materials/'+mid)['status']=='PUBLISH_PAYMENT_PENDING';creator.pay(listing['id'])
  poll(lambda:creator.call('GET','/materials/'+mid)['status']=='PUBLISHED','listing paid CDC publication')
  market=buyer.call('GET','/marketplace/materials/'+mid);assert 'sourceKey' not in market
  request={'materialId':mid,'materialVersion':market['version'],'licenseId':market['licenseId'],'acceptLicense':True,'idempotencyKey':uuid.uuid4().hex};order=buyer.call('POST','/orders',request);assert buyer.call('POST','/orders',request)['id']==order['id'];payment=buyer.pay(order['id']);oid=str(uuid.UUID(order['id']))
  def entitlement():
   return next((e for e in buyer.call('GET','/entitlements') if e['orderId']==oid),None)
  grant=poll(entitlement,'paid CDC entitlement');download=buyer.call('POST','/entitlements/'+grant['id']+'/download',{});assert download['expiresIn']<=120
  with urllib.request.urlopen(download['url']) as response:
   digest=hashlib.sha256()
   while chunk:=response.read(1024*1024):digest.update(chunk)
  assert digest.hexdigest()==source_hash
  creator.call('POST','/entitlements/'+grant['id']+'/download',{},403)
  net=order['fees']['creatorCent'];poll(lambda:creator.call('GET','/earnings/balance')['availableCent']==net,'earnings released',60)
  creator.call('PUT','/withdrawals/account',{'displayName':'Acceptance mock only','providerAccount':'mock-test-account'})
  w=creator.call('POST','/withdrawals',{'amountCent':net,'idempotencyKey':uuid.uuid4().hex});assert w['provider']=='MOCK';creator.call('POST','/dev/withdrawals/'+w['id']+'/complete',{'success':True});creator.call('POST','/dev/withdrawals/'+w['id']+'/complete',{'success':True})
  # Replay a real committed event through Kafka, then restart the business service.
  event=sql("SELECT JSON_OBJECT('database','framehub_video','table','outbox_event','type','INSERT','isDdl',FALSE,'data',JSON_ARRAY(JSON_OBJECT('event_id',event_id,'event_type',event_type,'event_version',event_version,'aggregate_type',aggregate_type,'aggregate_id',aggregate_id,'user_id',user_id,'payload',payload))) FROM outbox_event WHERE aggregate_id='"+oid+"' AND event_type='PAYMENT_SUCCEEDED'")
  docker('exec','-T','kafka','/opt/kafka/bin/kafka-console-producer.sh','--bootstrap-server','kafka:9092','--topic','video.outbox.cdc',input=event+'\n'+event+'\n')
  docker('restart','video-asset-service')
  def restarted():
   try:return buyer.call('GET','/orders/'+oid)['status']=='PAID'
   except (AssertionError,urllib.error.URLError):return False
  poll(restarted,'API restarted',120);time.sleep(12)
  assert int(sql("SELECT COUNT(*) FROM entitlement WHERE order_id='"+oid+"'"))==1
  assert int(sql("SELECT COUNT(*) FROM ledger_journal WHERE business_key='payment:"+oid+"'"))==1
  assert int(sql("SELECT COUNT(*) FROM ledger_journal WHERE business_key='release:"+oid+"'"))==1
  buyer.call('POST','/orders/'+oid+'/refund',{'idempotencyKey':'full-refund','reason':'Acceptance post-withdrawal refund'})
  buyer.call('POST','/entitlements/'+grant['id']+'/download',{},403)
  poll(lambda:next(e for e in buyer.call('GET','/entitlements') if e['id']==grant['id'])['status']=='REVOKED','refund CDC revocation')
  balance=creator.call('GET','/earnings/balance');assert balance['debtCent']==net and balance['payoutPendingCent']==0
  verify=creator.call('GET','/admin/ledger/verify');assert not verify['accountMismatches'] and not verify['unbalancedJournals']
  batch=creator.call('POST','/admin/reconciliation/run',{'day':datetime.datetime.now().date().isoformat()});assert batch['status']=='COMPLETED'
  differences=creator.call('GET','/admin/reconciliation/batches/'+batch['id']+'/differences');assert not differences,differences
  report.update(videoId=video,materialId=mid,listingOrderId=listing['id'],purchaseOrderId=oid,paymentId=payment['id'],entitlementId=grant['id'],withdrawalId=w['id'],sourceBytes=size,sourceHashVerified=True,hlsDecoded=True,duplicateEventsIdempotent=True,restartRecovered=True,refundRevoked=True,debtCent=net,ledgerBalanced=True,reconciliationDifferences=len(differences),mockOnly=True)
finally:
 if original_rule:
  original_rule.pop('version',None);original_rule['effectiveAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
  try:creator.call('POST','/admin/fee-rules',original_rule)
  except Exception:print('Could not restore local fee rule; check admin fee versions.')
pathlib.Path(a.result).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('PASS: all local commerce flows',json.dumps(report,ensure_ascii=False))
