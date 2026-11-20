package com.unfold;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Service
class Connections {
 final JdbcTemplate db; final Auth auth;
 Connections(JdbcTemplate db,Auth auth){this.db=db;this.auth=auth;}
 void consent(long id){if(auth.pilot()&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND consent_version='pilot-v1')",Boolean.class,id)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Accept the pilot privacy and community terms first");}
 void pair(long a,long b){
  consent(a);
  var rows=db.queryForList("SELECT id FROM accounts WHERE id IN (?,?) AND NOT suspended ORDER BY id FOR UPDATE",a,b);
  if(a==b||rows.size()!=2)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Connection unavailable");
  if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM blocks WHERE (actor=? AND target=?) OR (actor=? AND target=?))",Boolean.class,a,b,b,a)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Connection unavailable");
 }
 Map<String,Object> conversation(long id,long actor){
  consent(actor);
  var rows=db.queryForList("SELECT * FROM conversations WHERE id=? AND (low_id=? OR high_id=?)",id,actor,actor);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation unavailable");
  var c=rows.getFirst();pair(((Number)c.get("low_id")).longValue(),((Number)c.get("high_id")).longValue());
  c=db.queryForMap("SELECT * FROM conversations WHERE id=? FOR UPDATE",id);
  if(Boolean.TRUE.equals(c.get("closed")))throw new ResponseStatusException(HttpStatus.GONE,"This connection has ended");return c;
 }
 long other(Map<String,Object> c,long actor){long low=((Number)c.get("low_id")).longValue();return low==actor?((Number)c.get("high_id")).longValue():low;}
 void event(long user,String name){db.update("INSERT INTO events(user_id,name) VALUES (?,?)",user,name);}
}
