package fruition.access.beta;

import java.time.Instant;

/** 계정·동의·기본 워크스페이스 생성 트랜잭션 안에서만 발행한다. */
public record UserSignedUp(String userId, String email, Instant createdAt) {}
