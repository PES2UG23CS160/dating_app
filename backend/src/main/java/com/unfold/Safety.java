package com.unfold;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api") @Transactional
class Safety {
 final JdbcTemplate db;final Auth auth;final Connections guard;final Live live;
 Safety(JdbcTemplate db,Auth auth,Connections guard,Live live){this.db=db;this.auth=auth;this.guard=guard;this.live=live;}
 long id(Jwt jwt){return Long.parseLong(jwt.getSubject());}
 record Consent(@AssertTrue boolean adult,@AssertTrue boolean accepted,@Pattern(regexp="pilot-v1")@NotNull String version){}
 record Report(@NotBlank @Size(max=1000)String reason,Long messageId){}
 @PostMapping("/me/consent") Map<String,Boolean> consent(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Consent c){db.update("UPDATE accounts SET consent_version=?,consent_at=now() WHERE id=?",c.version(),id(jwt));return Map.of("ok",true);}
 @PostMapping("/profiles/{target}/block") Map<String,Boolean> block(@AuthenticationPrincipal Jwt jwt,@PathVariable long target){long who=id(jwt);if(who==target)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);var rows=db.queryForList("SELECT id FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",who,target);if(rows.size()!=2)throw new ResponseStatusException(HttpStatus.NOT_FOUND);db.update("INSERT INTO blocks(actor,target) VALUES (?,?) ON CONFLICT DO NOTHING",who,target);db.update("UPDATE conversations SET closed=true WHERE low_id=LEAST(?,?) AND high_id=GREATEST(?,?)",who,target,who,target);db.update("DELETE FROM answers an USING conversations c WHERE an.conversation_id=c.id AND c.low_id=LEAST(?,?) AND c.high_id=GREATEST(?,?) AND NOT EXISTS(SELECT 1 FROM rounds r WHERE r.conversation_id=an.conversation_id AND r.round=an.round AND r.state='revealed')",who,target,who,target);live.changed(who,target);return Map.of("ok",true);}
 @PostMapping("/profiles/{target}/report") Map<String,Boolean> report(@AuthenticationPrincipal Jwt jwt,@PathVariable long target,@Valid @RequestBody Report r){long who=id(jwt);if(who==target||db.queryForObject("SELECT count(*) FROM accounts WHERE id=?",Integer.class,target)==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND);if(db.queryForObject("SELECT count(*) FROM reports WHERE reporter=? AND created_at>now()-interval '1 hour'",Integer.class,who)>=5)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
  String evidence="";if(r.messageId()!=null){var rows=db.queryForList("SELECT m.body FROM messages m JOIN conversations c ON c.id=m.conversation_id WHERE m.id=? AND m.sender=? AND (c.low_id=? OR c.high_id=?)",r.messageId(),target,who,who);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Message cannot be attached");evidence=(String)rows.getFirst().get("body");}
  db.update("INSERT INTO reports(reporter,target,reason,evidence) VALUES (?,?,?,?)",who,target,r.reason().strip(),evidence);return Map.of("ok",true);
 }
 @DeleteMapping("/me") Map<String,Boolean> delete(@AuthenticationPrincipal Jwt jwt,HttpServletResponse response){long who=id(jwt); // Use the same ordered locks as conversation operations, including affected peers.
  var peers=db.queryForList("SELECT low_id,high_id FROM conversations WHERE low_id=? OR high_id=?",who,who);
  db.queryForList("SELECT id FROM accounts WHERE id=? OR id IN (SELECT low_id FROM conversations WHERE high_id=?) OR id IN (SELECT high_id FROM conversations WHERE low_id=?) ORDER BY id FOR UPDATE",who,who,who);
  db.update("DELETE FROM invitations WHERE email=(SELECT email FROM accounts WHERE id=?)",who);
  auth.logout(jwt,response);db.update("DELETE FROM accounts WHERE id=?",who);for(var p:peers)live.changed(((Number)p.get("low_id")).longValue(),((Number)p.get("high_id")).longValue());return Map.of("ok",true);
 }
 @PostMapping(value="/me/photo",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) Map<String,String> upload(@AuthenticationPrincipal Jwt jwt,@RequestParam MultipartFile file)throws IOException{
  long who=id(jwt);guard.consent(who);if(file.getSize()>2*1024*1024||file.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a JPEG or PNG under 2 MB");
  BufferedImage input;
  try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))){var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a JPEG or PNG");var reader=readers.next();try{reader.setInput(stream);if(!List.of("JPEG","PNG").contains(reader.getFormatName().toUpperCase(Locale.ROOT)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a JPEG or PNG");int w=reader.getWidth(0),h=reader.getHeight(0);if((long)w*h>20_000_000||w<1||h<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Image dimensions are too large");input=reader.read(0);}finally{reader.dispose();}}
  double scale=Math.min(1,1200.0/Math.max(input.getWidth(),input.getHeight()));var resized=new BufferedImage(Math.max(1,(int)(input.getWidth()*scale)),Math.max(1,(int)(input.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);var graphics=resized.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,resized.getWidth(),resized.getHeight());graphics.drawImage(input,0,0,resized.getWidth(),resized.getHeight(),null);graphics.dispose();var output=new ByteArrayOutputStream();ImageIO.write(resized,"jpeg",output);
  db.update("INSERT INTO photos(user_id,bytes) VALUES (?,?) ON CONFLICT(user_id) DO UPDATE SET bytes=EXCLUDED.bytes",who,output.toByteArray());String url="/api/photos/"+who;db.update("UPDATE profiles SET photo_url=? WHERE user_id=?",url,who);return Map.of("url",url);
 }
 @GetMapping("/photos/{target}") ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt,@PathVariable long target){long who=id(jwt);guard.consent(who);if(who!=target)guard.pair(who,target);var rows=db.query("SELECT bytes FROM photos WHERE user_id=?",(rs,n)->rs.getBytes(1),target);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(rows.getFirst());}
}
