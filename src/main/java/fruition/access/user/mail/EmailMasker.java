package fruition.access.user.mail;

/** 알림 메일에 새 이메일을 그대로 싣지 않도록 로컬 파트 앞 일부만 남기고 가린다. */
public final class EmailMasker {

    private static final int VISIBLE_PREFIX = 2;

    private EmailMasker() {}

    /** {@code newuser@example.com} → {@code ne*****@example.com}. 로컬 파트가 짧으면 첫 글자만 남긴다. */
    public static String mask(String email) {
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        int visible = local.length() > VISIBLE_PREFIX ? VISIBLE_PREFIX : 1;
        return local.substring(0, visible) + "*".repeat(Math.max(local.length() - visible, 3)) + email.substring(at);
    }
}
