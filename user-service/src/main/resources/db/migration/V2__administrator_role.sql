ALTER TABLE user_roles DROP CHECK ck_role;
ALTER TABLE user_roles ADD CONSTRAINT ck_role CHECK (role IN ('BUYER','CREATOR','ADMIN'));
