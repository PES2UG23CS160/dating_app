package com.unfold;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin") @Transactional
class Moderation {
 final JdbcTemplate db;final Auth auth;final Live live;
 Moderation(JdbcTemplate db,Auth auth,Live live){this.db=db;this.auth=auth;this.live=live;}
 long admin(Jwt jwt){long id=Long.parseLong(jwt.getSubject());auth.requireAdmin(id);return id;}
 void audit(long actor,String action,String target){db.update("INSERT INTO audit(actor,action,target) VALUES (?,?,?)",actor,action,target);}
 record Invite(@Email @NotBlank @Size(max=254)String email){}
 record Toggle(boolean enabled){}
 @GetMapping Map<String,Object> dashboard(@AuthenticationPrincipal Jwt jwt){long who=admin(jwt);audit(who,"view_report_queue","reports");return Map.of("reports",db.queryForList("SELECT * FROM reports WHERE expires_at>now() ORDER BY created_at DESC LIMIT 100"),"paused",db.queryForObject("SELECT invitations_paused FROM settings WHERE id=1",Boolean.class),"invitations",db.queryForList("SELECT email,created_at FROM invitations ORDER BY created_at DESC"),"metrics",db.queryForList("SELECT name,count(*) AS count FROM events GROUP BY name ORDER BY name"),"accounts",db.queryForList("SELECT a.id,a.email,a.suspended,p.name FROM accounts a LEFT JOIN profiles p ON p.user_id=a.id ORDER BY a.id"),"funnel",db.queryForMap("SELECT (SELECT count(*) FROM accounts) AS accounts,(SELECT count(*) FROM profiles) AS profiles,(SELECT count(*) FROM conversations WHERE NOT closed) AS matches,(SELECT count(*) FROM (SELECT conversation_id FROM messages GROUP BY conversation_id HAVING count(DISTINCT sender)=2) x) AS two_way_conversations,(SELECT count(*) FROM activities WHERE accepted) AS activities_accepted,(SELECT count(*) FROM activities WHERE round=3) AS activities_finished"));}
 @PostMapping("/invitations") Map<String,Boolean> invite(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Invite i){long who=admin(jwt);db.update("INSERT INTO invitations(email) VALUES (?) ON CONFLICT DO NOTHING",i.email().strip().toLowerCase(Locale.ROOT));audit(who,"invite",i.email().strip().toLowerCase(Locale.ROOT));return Map.of("ok",true);}
 @PostMapping("/pause") Map<String,Boolean> pause(@AuthenticationPrincipal Jwt jwt,@RequestBody Toggle t){long who=admin(jwt);db.update("UPDATE settings SET invitations_paused=? WHERE id=1",t.enabled());audit(who,"pause_invitations",Boolean.toString(t.enabled()));return Map.of("ok",true);}
 @PostMapping("/accounts/{target}/suspend") Map<String,Boolean> suspend(@AuthenticationPrincipal Jwt jwt,@PathVariable long target,@RequestBody Toggle t){long who=admin(jwt);if(who==target)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Cannot suspend your own moderator account");db.queryForList("SELECT id FROM accounts WHERE id=? FOR UPDATE",target);db.update("UPDATE accounts SET suspended=? WHERE id=?",t.enabled(),target);db.update("DELETE FROM sessions WHERE user_id=?",target);audit(who,t.enabled()?"suspend":"restore",Long.toString(target));live.changed(target);return Map.of("ok",true);}
 @PostMapping("/reports/{report}/resolve") Map<String,Boolean> resolve(@AuthenticationPrincipal Jwt jwt,@PathVariable long report){long who=admin(jwt);db.update("UPDATE reports SET status='resolved' WHERE id=?",report);audit(who,"resolve_report",Long.toString(report));return Map.of("ok",true);}
 @Scheduled(fixedDelay=3600000) void purge(){db.update("DELETE FROM reports WHERE expires_at<=now()");db.update("DELETE FROM sessions WHERE expires_at<=now()");db.update("DELETE FROM audit WHERE created_at<now()-interval '90 days'");db.update("DELETE FROM events WHERE created_at<now()-interval '30 days'");}
}
