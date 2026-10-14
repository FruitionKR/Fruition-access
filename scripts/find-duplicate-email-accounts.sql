-- 같은 이메일로 계정이 둘 이상인 사용자를 찾는다(Fruition-access #31).
-- 신규 가입은 #31부터 막히지만, 그 전에 생긴 중복 계정은 그대로 남아 있다. 어떤 계정이 있는지 보고 처리 방침을 정할 때 쓴다.
-- 실행: psql "$ACCESS_DATABASE_URL" -f scripts/find-duplicate-email-accounts.sql
SELECT u.email,
       count(*)                                        AS account_count,
       string_agg(u.provider, ',' ORDER BY u.provider) AS providers,
       string_agg(u.id, ',' ORDER BY u.created_at)     AS user_ids,
       min(u.created_at)                               AS first_created_at,
       max(u.created_at)                               AS last_created_at
FROM users u
GROUP BY u.email
HAVING count(*) > 1
ORDER BY last_created_at DESC;
