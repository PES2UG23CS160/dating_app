package com.unfold;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
class PilotReadiness implements ApplicationRunner {
 private final Auth auth;private final JdbcTemplate db;
 PilotReadiness(Auth auth,JdbcTemplate db){this.auth=auth;this.db=db;}
 public void run(ApplicationArguments args){if(auth.pilot()&&db.queryForObject("SELECT count(*) FROM accounts WHERE password_hash IS NOT NULL",Integer.class)>0)throw new IllegalStateException("Pilot must use a separate empty database, never a demo database");}
}
