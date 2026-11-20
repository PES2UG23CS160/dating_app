package com.unfold;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api")
class Auth {
 final JdbcTemplate db; final PasswordEncoder passwords; final JwtEncoder encoder;
 final String mode,client,admin,support; final boolean secure;
 private final JwtDecoder google= NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
 Auth(JdbcTemplate db,PasswordEncoder passwords,JwtEncoder encoder,@Value("${unfold.mode}")String mode,@Value("${unfold.google-client-id}")String client,@Value("${unfold.secure-cookie}")boolean secure,@Value("${unfold.admin-email}")String admin,@Value("${unfold.support-email}")String support){this.db=db;this.passwords=passwords;this.encoder=encoder;this.mode=mode;this.client=client;this.secure=secure;this.admin=admin;this.support=support;
  if(!List.of("demo","pilot").contains(mode))throw new IllegalArgumentException("APP_MODE must be demo or pilot");
  if(mode.equals("pilot")&&(!secure||client.isBlank()||admin.isBlank()||support.isBlank()))throw new IllegalArgumentException("Pilot requires secure cookies, Google client ID, admin and support email");
 }
 boolean pilot(){return mode.equals("pilot");}
 boolean admin(long id){return !admin.isBlank()&&Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND lower(email)=lower(?))",Boolean.class,id,admin));}
 void requireAdmin(long id){if(!admin(id))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Moderator access required");}
 record Credentials(@NotBlank @Email @Size(max=254)String email,@NotBlank @Size(min=10,max=72)String password){}
 record Google(@NotBlank String credential){}
 @GetMapping("/config") Map<String,Object> config(){return Map.of("mode",mode,"googleClientId",client,"supportEmail",support,"consentVersion","pilot-v1");}
 @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken(),"header",token.getHeaderName());}
 @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
 Map<String,Object> register(@Valid @RequestBody Credentials c,HttpServletResponse response){
  demoOnly();if(c.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password is too long");
  try{long id=db.queryForObject("INSERT INTO accounts(email,password_hash) VALUES (?,?) RETURNING id",Long.class,c.email().strip().toLowerCase(Locale.ROOT),passwords.encode(c.password()));return issue(id,response);}
  catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Email already registered");}
 }
 @PostMapping("/auth/login") Map<String,Object> login(@Valid @RequestBody Credentials c,HttpServletResponse response){demoOnly();var rows=db.queryForList("SELECT id,password_hash FROM accounts WHERE email=? AND NOT suspended",c.email().strip().toLowerCase(Locale.ROOT));if(rows.isEmpty()||!passwords.matches(c.password(),(String)rows.getFirst().get("password_hash")))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Email or password is incorrect");return issue(((Number)rows.getFirst().get("id")).longValue(),response);}
 @PostMapping("/auth/google") @Transactional Map<String,Object> google(@Valid @RequestBody Google body,HttpServletResponse response){
  if(!pilot())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  Jwt g;try{g=google.decode(body.credential());}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Google sign-in failed");}
  if(!g.getAudience().contains(client)||!List.of("https://accounts.google.com","accounts.google.com").contains(g.getClaimAsString("iss"))||!Boolean.TRUE.equals(g.getClaimAsBoolean("email_verified")))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  String email=g.getClaimAsString("email").toLowerCase(Locale.ROOT);
  // Restrict pilot invitations to provider-authoritative email addresses.
  if(!email.endsWith("@gmail.com")&&g.getClaimAsString("hd")==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Use an invited Gmail or Google Workspace account");
  var existing=db.queryForList("SELECT id,suspended FROM accounts WHERE google_subject=?",g.getSubject());
  long id;
  if(existing.isEmpty()){
   boolean invited=admin.equalsIgnoreCase(email)||Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM invitations,settings WHERE email=? AND NOT invitations_paused)",Boolean.class,email));
   if(!invited)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Pilot invitations are required or currently paused");
   id=db.queryForObject("INSERT INTO accounts(email,google_subject) VALUES (?,?) RETURNING id",Long.class,email,g.getSubject());
  }else{if(Boolean.TRUE.equals(existing.getFirst().get("suspended")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account suspended");id=((Number)existing.getFirst().get("id")).longValue();}
  return issue(id,response);
 }
 private void demoOnly(){if(pilot())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"The pilot uses invited Google accounts only");}
 private Map<String,Object> issue(long id,HttpServletResponse response){Instant now=Instant.now();UUID sid=UUID.randomUUID();db.update("INSERT INTO sessions(id,user_id,expires_at) VALUES (?,?,?)",sid,id,java.sql.Timestamp.from(now.plusSeconds(7200)));var claims=JwtClaimsSet.builder().issuer("unfold").subject(Long.toString(id)).id(sid.toString()).issuedAt(now).expiresAt(now.plusSeconds(7200)).build();String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();cookie(response,token,7200);return Map.of("id",id,"expiresIn",7200);}
 private void cookie(HttpServletResponse response,String value,int seconds){response.addHeader("Set-Cookie",ResponseCookie.from("unfold_session",value).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(seconds).build().toString());}
 @PostMapping("/auth/logout") Map<String,Boolean> logout(@AuthenticationPrincipal Jwt jwt,HttpServletResponse response){if(jwt!=null)db.update("DELETE FROM sessions WHERE id=?",UUID.fromString(jwt.getId()));cookie(response,"",0);return Map.of("ok",true);}
}
