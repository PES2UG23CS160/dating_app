CREATE TABLE accounts (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 email TEXT NOT NULL UNIQUE,
 password_hash TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE profiles (
 user_id BIGINT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
 name VARCHAR(40) NOT NULL,
 birth_date DATE NOT NULL,
 city VARCHAR(80) NOT NULL,
 gender VARCHAR(20) NOT NULL CHECK (gender IN ('Woman','Man','Nonbinary')),
 interested_in VARCHAR(20) NOT NULL CHECK (interested_in IN ('Everyone','Woman','Man','Nonbinary')),
 min_age INT NOT NULL CHECK(min_age >= 18),
 max_age INT NOT NULL CHECK(max_age >= min_age AND max_age <= 100),
 bio VARCHAR(500) NOT NULL,
 prompt VARCHAR(240) NOT NULL,
 interests VARCHAR(200) NOT NULL,
 photo_url VARCHAR(1000) NOT NULL DEFAULT '',
 CHECK (birth_date <= CURRENT_DATE - INTERVAL '18 years')
);
CREATE TABLE decisions (
 actor_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 target_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
 liked BOOLEAN NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(actor_id, target_id),
 CHECK(actor_id <> target_id)
);
CREATE INDEX decisions_target ON decisions(target_id, actor_id) WHERE liked;
