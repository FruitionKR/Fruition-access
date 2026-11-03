-- PDF 변환 때 그림을 외부 AI(워크스페이스 AI 모델)로 보내 설명을 붙일지 여부. 외부 전송·비용이 생기므로 기본은 끈다.
-- 상수 DEFAULT가 있는 ADD COLUMN은 PostgreSQL 11+에서 테이블을 다시 쓰지 않는 메타데이터 변경이다.
-- ACCESS EXCLUSIVE 잠금을 잠깐 잡으므로 앞선 긴 트랜잭션 뒤에 줄 서서 다른 요청을 막지 않게 5초에서 포기한다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE workspaces ADD COLUMN IF NOT EXISTS describe_pdf_figures boolean NOT NULL DEFAULT false;
