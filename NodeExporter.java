import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class NodeExporter {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(9100), 0);
        server.createContext("/metrics", new MetricsExporter());
        server.setExecutor(null);
        server.start();
        System.out.println("NodeExporter started on port 9100");
    }

    static class MetricsExporter implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            System.out.println("Handling request");
            StringBuilder response = new StringBuilder();
            
            scrapeLoadAvg(response);
            
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.toString().getBytes());
            os.close();
        }
    }

    private static void scrapeLoadAvg(StringBuilder sb) {
        try {
            Process p = Runtime.getRuntime().exec("uptime");
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, one, five, fifteen;
            one = five = fifteen = "";
            
            line = reader.readLine();
            if (line != null) {
                Pattern loadNums = Pattern.compile("load average: (\\d\\.\\d\\d), (\\d\\.\\d\\d), (\\d\\.\\d\\d)");
                Matcher m = loadNums.matcher(line);
                if (m.find()) {
                    one = m.group(1);
                    five = m.group(2);
                    fifteen = m.group(3);
                }
            }
            reader.close();
            
            if ((one == "") || (five == "") || (fifteen == "")) {
                // TODO raise a custom exception type here
                sb.append("# Error parsing uptime metrics: ").append(line).append("\n");
            } else {
                // Output metrics
                sb.append("# HELP node_load1 1m load average.\n");
                sb.append("# TYPE node_load1 gauge\n");
                sb.append("node_load1 ").append(one).append("\n");
                sb.append("# HELP node_load15 15m load average.\n");
                sb.append("# TYPE node_load15 gauge\n");
                sb.append("node_load15 ").append(fifteen).append("\n");
                sb.append("# HELP node_load5 5m load average.\n");
                sb.append("# TYPE node_load5 gauge\n");
                sb.append("node_load5 ").append(five).append("\n");
            }
        } catch (IOException e) {
            // TODO replace this with a more meaningful exception
            sb.append("# Error reading uptime metrics: ").append(e.getMessage()).append("\n");
        }
    }
}
