CREATE USER IF NOT EXISTS 'nodeapp'@'%' IDENTIFIED WITH mysql_native_password BY 'nodepass';
GRANT SELECT,INSERT,UPDATE ON picturesdb.* TO 'nodeapp'@'%';
GRANT FILE ON *.* TO 'nodeapp'@'%';
FLUSH PRIVILEGES;