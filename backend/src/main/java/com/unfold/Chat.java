package com.unfold;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/conversations") @Transactional
class Chat {
 final JdbcTemplate db; final Connections guard; final Live live;
 static final List<String> PROMPTS=List.of("What does a really good ordinary Sunday look like for you?","What small gesture makes you feel appreciated?","What would make a first meeting comfortable for you?");
 Chat(JdbcTemplate db,Connections guard,Live live){this.db=db;this.guard=guard;this.live=live;}
 long actor(Jwt jwt){return Long.parseLong(jwt.getSubject());}
 record Message(@NotNull UUID clientId,@NotBlank @Size(max=2000)String body){}
 record Answer(@Min(0)@Max(2)int round,@NotBlank @Size(max=500)String body){}
 record Round(@Min(0)@Max(2)int round){}
 @GetMapping("/{id}/messages") List<Map<String,Object>> messages(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestParam(defaultValue="9223372036854775807")long before){guard.conversation(id,actor(jwt));return db.queryForList("SELECT id,sender,body,client_id,created_at FROM messages WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT 50",id,before);}
 @PostMapping("/{id}/messages") Map<String,Object> message(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@Valid @RequestBody Message m){long who=actor(jwt);var c=guard.conversation(id,who);
  var old=db.queryForList("SELECT * FROM messages WHERE sender=? AND client_id=?",who,m.clientId());
  if(!old.isEmpty()){var existing=old.getFirst();if(((Number)existing.get("conversation_id")).longValue()!=id||!existing.get("body").equals(m.body().strip()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Retry identifier already used");return existing;}
  if(db.queryForObject("SELECT count(*) FROM messages WHERE sender=? AND created_at>now()-interval '1 minute'",Integer.class,who)>=30)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Please slow down");
  var result=db.queryForMap("INSERT INTO messages(conversation_id,sender,client_id,body) VALUES (?,?,?,?) RETURNING id,sender,body,client_id,created_at",id,who,m.clientId(),m.body().strip());guard.event(who,"message_sent");live.changed(who,guard.other(c,who));return result;
 }
 @PostMapping("/{id}/unmatch") Map<String,Boolean> unmatch(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){long who=actor(jwt);var c=guard.conversation(id,who);db.update("UPDATE conversations SET closed=true WHERE id=?",id);db.update("DELETE FROM answers WHERE conversation_id=? AND round NOT IN (SELECT round FROM rounds WHERE conversation_id=? AND state='revealed')",id,id);live.changed(who,guard.other(c,who));return Map.of("ok",true);}
 @GetMapping("/{id}/activity") Map<String,Object> activity(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){long who=actor(jwt);guard.conversation(id,who);var rows=db.queryForList("SELECT * FROM activities WHERE conversation_id=?",id);if(rows.isEmpty())return Map.of("status","none","prompts",PROMPTS);
  var a=rows.getFirst();int round=((Number)a.get("round")).intValue();List<Map<String,Object>> history=new ArrayList<>();
  for(var r:db.queryForList("SELECT round,state FROM rounds WHERE conversation_id=? ORDER BY round",id)){var h=new HashMap<String,Object>(r);if(r.get("state").equals("revealed"))h.put("answers",db.queryForList("SELECT user_id,body FROM answers WHERE conversation_id=? AND round=? ORDER BY user_id",id,r.get("round")));history.add(h);}
  var mine=round<3?db.queryForList("SELECT body FROM answers WHERE conversation_id=? AND round=? AND user_id=?",id,round,who):List.of();
  return Map.of("status",round==3?"complete":Boolean.TRUE.equals(a.get("accepted"))?"active":"proposed","proposer",a.get("proposer"),"round",round,"prompts",PROMPTS,"history",history,"submitted",!mine.isEmpty());
 }
 @PostMapping("/{id}/activity/propose") Map<String,Object> propose(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){long who=actor(jwt);var c=guard.conversation(id,who);db.update("INSERT INTO activities(conversation_id,proposer) VALUES (?,?) ON CONFLICT DO NOTHING",id,who);live.changed(who,guard.other(c,who));return activity(jwt,id);}
 @PostMapping("/{id}/activity/accept") Map<String,Object> accept(@AuthenticationPrincipal Jwt jwt,@PathVariable long id){long who=actor(jwt);var c=guard.conversation(id,who);int n=db.update("UPDATE activities SET accepted=true WHERE conversation_id=? AND proposer<>? AND NOT accepted",id,who);if(n>0)guard.event(who,"activity_accepted");live.changed(who,guard.other(c,who));return activity(jwt,id);}
 private void current(long id,int round){if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM activities WHERE conversation_id=? AND accepted AND round=?)",Boolean.class,id,round)))throw new ResponseStatusException(HttpStatus.CONFLICT,"This card has moved on; refresh the conversation");}
 @PostMapping("/{id}/activity/answer") Map<String,Object> answer(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@Valid @RequestBody Answer a){long who=actor(jwt);var c=guard.conversation(id,who);current(id,a.round());
  var old=db.queryForList("SELECT body FROM answers WHERE conversation_id=? AND round=? AND user_id=?",id,a.round(),who);if(!old.isEmpty()&&!old.getFirst().get("body").equals(a.body().strip()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Submitted answers cannot be changed");
  db.update("INSERT INTO answers(conversation_id,round,user_id,body) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",id,a.round(),who,a.body().strip());
  if(db.queryForObject("SELECT count(*) FROM answers WHERE conversation_id=? AND round=?",Integer.class,id,a.round())==2){db.update("INSERT INTO rounds(conversation_id,round,state) VALUES (?,?,'revealed')",id,a.round());db.update("UPDATE activities SET round=round+1 WHERE conversation_id=?",id);guard.event(who,"card_revealed");if(a.round()==2)guard.event(who,"activity_completed");}
  live.changed(who,guard.other(c,who));return activity(jwt,id);
 }
 @PostMapping("/{id}/activity/skip") Map<String,Object> skip(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@Valid @RequestBody Round r){long who=actor(jwt);var c=guard.conversation(id,who);current(id,r.round());db.update("DELETE FROM answers WHERE conversation_id=? AND round=?",id,r.round());db.update("INSERT INTO rounds(conversation_id,round,state) VALUES (?,?,'skipped')",id,r.round());db.update("UPDATE activities SET round=round+1 WHERE conversation_id=?",id);live.changed(who,guard.other(c,who));return activity(jwt,id);}
}
