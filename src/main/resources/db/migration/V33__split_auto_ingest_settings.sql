-- 자동 편입 설정을 경로별로 나눈다(ADR-0019 갱신). Markdown·txt 업로드 직후 편입과 PDF 변환이 끝난 뒤 편입을 따로 켠다.
-- Markdown 업로드는 제품 기본이 자동 편입이라 기존 워크스페이스도 모두 켠다.
-- PDF 변환 뒤 편입은 비용이 커서 기본은 끄고, 기존 워크스페이스는 지금까지 두 경로를 함께 켜던 auto_ingest_on_upload 값을 이어받는다.
ALTER TABLE workspaces ADD COLUMN auto_ingest_markdown_on_upload boolean NOT NULL DEFAULT true;
ALTER TABLE workspaces ADD COLUMN auto_ingest_pdf_on_convert boolean NOT NULL DEFAULT false;
UPDATE workspaces SET auto_ingest_pdf_on_convert = auto_ingest_on_upload;

-- auto_ingest_on_upload는 지우지 않는다. 배포 중 함께 떠 있는 옛 pod가 이 컬럼을 읽고 쓰며,
-- 새 코드는 이 값을 auto_ingest_pdf_on_convert와 같게 유지한다(하위호환 응답 auto_ingest_on_upload와 같은 의미).
