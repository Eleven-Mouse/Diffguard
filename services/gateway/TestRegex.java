import java.util.regex.Pattern;
public class TestRegex {
    static Pattern SQL_CONCAT = Pattern.compile(
        "(?i)(\"\\s*\\+\\s*\\w+.*(?:SELECT|INSERT|UPDATE|DELETE|FROM|WHERE))|" +
        "(?i)((?:SELECT|INSERT|UPDATE|DELETE|FROM|WHERE).*\\+\\s*\")"
    );
    public static void main(String[] args) {
        String[] tests = {
            "String query = \"SELECT * FROM users WHERE id = \" + userId;",
            "String sql = \"INSERT INTO users VALUES(\" + val + \")\";",
            "query = \"SELECT * FROM users WHERE id = \" + user_id",
            "String x = \" + variable + \" WHERE condition",
            "SELECT * FROM table + \"",
            "\" + variable + \" WHERE x",
            "  query = \" + val + \" FROM table",
        };
        for (String t : tests) {
            System.out.println(t.trim() + " => " + SQL_CONCAT.matcher(t.trim()).find());
        }
    }
}
