package com.unfold;

import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class Api {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;
    private final Connections connections; private final Auth auth;
    Api(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder tokens, Connections connections, Auth auth) {
        this.db = db; this.passwords = passwords; this.tokens = tokens; this.connections=connections; this.auth=auth;
    }
    record Credentials(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(min=10,max=72) String password) {}
    record Profile(@NotBlank @Size(max=40) String name, @NotNull @Past LocalDate birthDate,
        @NotBlank @Size(max=80) String city, @Pattern(regexp="Woman|Man|Nonbinary") @NotNull String gender,
        @Pattern(regexp="Everyone|Woman|Man|Nonbinary") @NotNull String interestedIn,
        @Min(18) @Max(100) int minAge, @Min(18) @Max(100) int maxAge,
        @NotBlank @Size(max=500) String bio, @NotBlank @Size(max=240) String prompt,
        @NotBlank @Size(max=200) String interests, @NotNull @Size(max=1000) String photoUrl) {}
    record Decision(@NotNull Boolean liked) {}
    @GetMapping("/health") Map<String,String> health() { db.queryForObject("SELECT 1", Integer.class); return Map.of("status","ok"); }
    private long id(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
    @GetMapping("/me") Map<String,Object> me(@AuthenticationPrincipal Jwt jwt) {
        var profiles = db.queryForList("SELECT * FROM profiles WHERE user_id=?",id(jwt));
        return Map.of("id",id(jwt),"admin",auth.admin(id(jwt)),"consented",Boolean.TRUE.equals(db.queryForObject("SELECT consent_version IS NOT NULL FROM accounts WHERE id=?",Boolean.class,id(jwt))),"profile",profiles.isEmpty() ? Map.of() : profiles.getFirst());
    }
    @PutMapping("/me/profile") Map<String,Object> save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Profile p) {
        connections.consent(id(jwt));
        if(auth.pilot()&&!p.city().strip().equalsIgnoreCase("Bengaluru"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This pilot is for Bengaluru residents");
        if (Period.between(p.birthDate(),LocalDate.now()).getYears() < 18 || Period.between(p.birthDate(),LocalDate.now()).getYears() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unfold is for adults aged 18–100");
        if (p.minAge() > p.maxAge()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Minimum age must not exceed maximum age");
        if(p.photoUrl().startsWith("/api/photos/")&&!p.photoUrl().equals("/api/photos/"+id(jwt)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use your own photo");
        if(auth.pilot()&&!p.photoUrl().isBlank()&&!p.photoUrl().equals("/api/photos/"+id(jwt)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload your photo using the photo control");
        if (!p.photoUrl().startsWith("/api/photos/") && !p.photoUrl().isBlank() && !p.photoUrl().matches("https://[^\\s]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Photo URL must use HTTPS");
        db.update("""
            INSERT INTO profiles(user_id,name,birth_date,city,gender,interested_in,min_age,max_age,bio,prompt,interests,photo_url)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET
            name=EXCLUDED.name,birth_date=EXCLUDED.birth_date,city=EXCLUDED.city,gender=EXCLUDED.gender,
            interested_in=EXCLUDED.interested_in,min_age=EXCLUDED.min_age,max_age=EXCLUDED.max_age,
            bio=EXCLUDED.bio,prompt=EXCLUDED.prompt,interests=EXCLUDED.interests,photo_url=EXCLUDED.photo_url
            """,id(jwt),p.name().strip(),p.birthDate(),p.city().strip(),p.gender(),p.interestedIn(),p.minAge(),p.maxAge(),p.bio().strip(),p.prompt().strip(),p.interests().strip(),p.photoUrl().strip());
        return me(jwt);
    }
    private static final String PUBLIC_FIELDS = "p.user_id AS id,p.name,EXTRACT(YEAR FROM age(p.birth_date))::int AS age,p.city,p.bio,p.prompt,p.interests,p.photo_url";
    @GetMapping("/discover") List<Map<String,Object>> discover(@AuthenticationPrincipal Jwt jwt) {
        connections.consent(id(jwt));
        return db.queryForList("SELECT " + PUBLIC_FIELDS + """
             FROM profiles p JOIN profiles me ON me.user_id=?
             WHERE p.user_id<>me.user_id
             AND EXISTS(SELECT 1 FROM accounts ac WHERE ac.id=p.user_id AND NOT ac.suspended)
             AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.actor=me.user_id AND bl.target=p.user_id) OR (bl.actor=p.user_id AND bl.target=me.user_id))
             AND NOT EXISTS(SELECT 1 FROM conversations co WHERE co.low_id=LEAST(me.user_id,p.user_id) AND co.high_id=GREATEST(me.user_id,p.user_id) AND co.closed)
             AND (me.interested_in='Everyone' OR me.interested_in=p.gender)
             AND (p.interested_in='Everyone' OR p.interested_in=me.gender)
             AND EXTRACT(YEAR FROM age(p.birth_date)) BETWEEN me.min_age AND me.max_age
             AND EXTRACT(YEAR FROM age(me.birth_date)) BETWEEN p.min_age AND p.max_age
             AND NOT EXISTS (SELECT 1 FROM decisions d WHERE d.actor_id=me.user_id AND d.target_id=p.user_id)
             ORDER BY p.user_id LIMIT 50
            """,id(jwt));
    }
    @PostMapping("/profiles/{target}/decision") @Transactional
    Map<String,Boolean> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable long target, @Valid @RequestBody Decision decision) {
        long actor = id(jwt);
        if (actor==target) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"You cannot like yourself");
        connections.pair(actor,target);
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM conversations WHERE low_id=LEAST(?,?) AND high_id=GREATEST(?,?) AND closed)",Boolean.class,actor,target,actor,target)))throw new ResponseStatusException(HttpStatus.GONE,"This connection has ended");
        // Lock both accounts in a stable order so simultaneous likes see a consistent match.
        db.queryForList("SELECT id FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE", actor,target);
        if (db.queryForObject("SELECT count(*) FROM profiles WHERE user_id IN (?,?)",Integer.class,actor,target)!=2)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Both people need completed profiles");
        db.update("INSERT INTO decisions(actor_id,target_id,liked) VALUES (?,?,?) ON CONFLICT(actor_id,target_id) DO NOTHING",actor,target,decision.liked());
        boolean matched = Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM decisions a JOIN decisions b ON a.actor_id=b.target_id AND a.target_id=b.actor_id
            WHERE a.actor_id=? AND a.target_id=? AND a.liked AND b.liked)
            """,Boolean.class,actor,target));
        if(matched)db.update("INSERT INTO conversations(low_id,high_id) VALUES (LEAST(?,?),GREATEST(?,?)) ON CONFLICT DO NOTHING",actor,target,actor,target);
        return Map.of("matched",matched);
    }
    @GetMapping("/matches") List<Map<String,Object>> matches(@AuthenticationPrincipal Jwt jwt) {
        return db.queryForList("SELECT " + PUBLIC_FIELDS + ", co.id AS conversation_id" + """
             FROM decisions a JOIN decisions b ON a.actor_id=b.target_id AND a.target_id=b.actor_id
             JOIN profiles p ON p.user_id=a.target_id
             JOIN conversations co ON co.low_id=LEAST(a.actor_id,a.target_id) AND co.high_id=GREATEST(a.actor_id,a.target_id) AND NOT co.closed
             JOIN accounts ac ON ac.id=p.user_id AND NOT ac.suspended
             WHERE a.actor_id=? AND a.liked AND b.liked
             AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.actor=a.actor_id AND bl.target=p.user_id) OR (bl.actor=p.user_id AND bl.target=a.actor_id)) ORDER BY GREATEST(a.created_at,b.created_at) DESC
            """,id(jwt));
    }
}
