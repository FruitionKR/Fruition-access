-- 사용자가 고른 화면 언어(ko·en). 고른 적 없으면 null이고, 그때 화면·메일은 요청 언어(Accept-Language)를 따른다.
-- 값은 애플리케이션이 검증하지만, 다른 값이 저장돼 언어 선택이 어긋나지 않게 CHECK로도 막는다.
ALTER TABLE users ADD COLUMN locale varchar(8) CONSTRAINT users_locale_check CHECK (locale IN ('ko', 'en'));

-- 이메일 변경 알림은 요청 밖에서 발송하므로, 넣는 시점에 고른 메일 언어를 함께 저장한다.
-- 기존 행과 null은 한국어로 보낸다.
ALTER TABLE email_change_notifications ADD COLUMN locale varchar(8);
