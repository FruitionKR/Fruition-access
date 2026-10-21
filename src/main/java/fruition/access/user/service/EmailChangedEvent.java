package fruition.access.user.service;

import java.time.Instant;

/** 이메일 변경 트랜잭션 안에서 발행해, 기존 이메일로 보낼 알림을 outbox에 넣게 하는 이벤트({@link EmailChangeNotificationListener}). */
public record EmailChangedEvent(String userId, String previousEmail, String newEmail, Instant changedAt) {}
