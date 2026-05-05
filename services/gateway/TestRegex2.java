import java.util.regex.Pattern;
public class TestRegex2 {
    static Pattern SECRET = Pattern.compile(
        "(?i)(password|passwd|secret|api_key|apikey|token|private_key)\\s*[:=]\\s*[\"'][^\"']{8,}[\"']"
    );
    static Pattern AWS = Pattern.compile("AKIA[0-9A-Z]{16}");
    static Pattern GH = Pattern.compile("gh[ps]_[A-Za-z0-9_]{36,}");
    public static void main(String[] args) {
        String[] secretTests = {
            "String pass = \"password\";",
            "String dbUrl = \"jdbc:mysql://localhost?password=mySecret123\";",
            "password = \"short\"",
            "password = \"mylongpassword123\"",
            "api_key = \"sk-12345678abcdefgh\"",
            "String awsKey = \"AKIAIOSFODNN7EXAMPLE\";",
            "String token = \"ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij1234\";",
        };
        System.out.println("=== SECRET ===");
        for (String t : secretTests) {
            System.out.println(t + " => S:" + SECRET.matcher(t).find() + " A:" + AWS.matcher(t).find() + " G:" + GH.matcher(t).find());
        }
    }
}
