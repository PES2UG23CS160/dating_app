package com.unfold;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
@Configuration
class SecurityConfig {
 @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder();}
 @Bean SecretKeySpec jwtKey(@Value("${unfold.jwt-secret}") String secret){if(secret.length()<44)throw new IllegalArgumentException("JWT_SECRET must have at least 44 random characters");return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256");}
 @Bean JwtEncoder encoder(SecretKeySpec key){return new NimbusJwtEncoder(new ImmutableSecret<>(key));}
 @Bean JwtDecoder decoder(SecretKeySpec key,JdbcTemplate db){
  var decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  OAuth2TokenValidator<Jwt> session=jwt->{try{
   boolean valid=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM sessions s JOIN accounts a ON a.id=s.user_id WHERE s.id=? AND s.user_id=? AND s.expires_at>now() AND NOT a.suspended)",Boolean.class,UUID.fromString(jwt.getId()),Long.parseLong(jwt.getSubject())));
   if(valid)return OAuth2TokenValidatorResult.success();
  }catch(Exception ignored){}
  return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));};
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("unfold"),session));return decoder;
 }
 @Bean SecurityFilterChain security(HttpSecurity http,@Value("${unfold.secure-cookie}") boolean secure) throws Exception {
  var csrf=new CookieCsrfTokenRepository();csrf.setCookieCustomizer(c->c.httpOnly(true).secure(secure).sameSite("Lax").path("/"));
  return http.csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/api/auth/**","/api/health","/api/config","/api/csrf","/error").permitAll().requestMatchers("/api/**","/ws").authenticated().anyRequest().permitAll())
   .oauth2ResourceServer(o->o.bearerTokenResolver(request->{if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals("unfold_session"))return c.getValue();return null;}).jwt(j->{}))
   .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; frame-src https://accounts.google.com; style-src 'self' 'unsafe-inline' https://accounts.google.com; connect-src 'self' https://accounts.google.com; img-src 'self' https: data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
   .build();
 }
}
