package com.unfold;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuthTest {
 private Auth auth(JdbcTemplate db){return new Auth(db,new BCryptPasswordEncoder(),mock(JwtEncoder.class),"pilot","expected-client",true,"owner@gmail.com","support@example.test");}
 @Test void pilotRequiresConfiguration(){assertThrows(IllegalArgumentException.class,()->new Auth(mock(JdbcTemplate.class),new BCryptPasswordEncoder(),mock(JwtEncoder.class),"pilot","",false,"",""));}
 @Test void pilotRejectsPasswordRegistration(){assertEquals(403,assertThrows(ResponseStatusException.class,()->auth(mock(JdbcTemplate.class)).register(new Auth.Credentials("a@example.test","password-12345"),new MockHttpServletResponse())).getStatusCode().value());}
 @Test void pilotRejectsInvalidGoogleAudienceBeforeDatabaseAccess(){var db=mock(JdbcTemplate.class);var auth=auth(db);var decoder=mock(JwtDecoder.class);ReflectionTestUtils.setField(auth,"google",decoder);when(decoder.decode("credential")).thenReturn(Jwt.withTokenValue("credential").header("alg","RS256").subject("subject").issuer("https://accounts.google.com").audience(List.of("other-client")).claim("email_verified",true).claim("email","person@gmail.com").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());assertEquals(401,assertThrows(ResponseStatusException.class,()->auth.google(new Auth.Google("credential"),new MockHttpServletResponse())).getStatusCode().value());verifyNoInteractions(db);}
 @Test void pilotRejectsNonAuthoritativeThirdPartyGoogleEmail(){var db=mock(JdbcTemplate.class);var auth=auth(db);var decoder=mock(JwtDecoder.class);ReflectionTestUtils.setField(auth,"google",decoder);when(decoder.decode("credential")).thenReturn(Jwt.withTokenValue("credential").header("alg","RS256").subject("subject").issuer("https://accounts.google.com").audience(List.of("expected-client")).claim("email_verified",true).claim("email","person@outside.example").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());assertEquals(403,assertThrows(ResponseStatusException.class,()->auth.google(new Auth.Google("credential"),new MockHttpServletResponse())).getStatusCode().value());verifyNoInteractions(db);}
}
