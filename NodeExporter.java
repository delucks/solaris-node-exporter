/* NodeExporter - a prometheus node_exporter compatible service for Solaris 10
 * Copyright (C) 2026, Jamie Luck (delucks)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
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
            scrapeSystemMisc(response);
            scrapeArp(response);
            
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.toString().getBytes());
            os.close();
        }
    }

    private static void scrapeArp(StringBuilder sb) {
        try {
            Process p = new ProcessBuilder("arp", "-a", "-n").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            HashMap<String, Integer> deviceCounts = new HashMap<String, Integer>();

            String line;
            while ((line = reader.readLine()) != null) {
                // Skip header lines
                if ((line.contains("Device") && line.contains("IP Address")) || ((line.contains("------"))) || ((line.contains("Net to Media Table")))) {
                    continue;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+", 2);
                if (parts.length > 0) {
                    String device = parts[0];
                    Integer value = deviceCounts.containsKey(device) ? deviceCounts.get(device) : 0;
                    deviceCounts.put(device, value + 1);
                }
            }
            reader.close();

            // Output node_arp_entries metrics
            if (deviceCounts.isEmpty()) {
                sb.append("# Error reading arp metrics: no devices returned\n");
            } else {
                sb.append("# HELP node_arp_entries Number of ARP entries for each device.\n");
                sb.append("# TYPE node_arp_entries gauge\n");
                for (Map.Entry<String, Integer> entry : deviceCounts.entrySet()) {
                    sb.append(String.format("node_arp_entries{device=\"%s\"} %d\n", entry.getKey(), entry.getValue()));
                }
            }
        } catch (IOException e) {
            // TODO exception handling
            sb.append("# Error reading arp metrics: ").append(e.getMessage()).append("\n");
        }
    }

    private static void scrapeSystemMisc(StringBuilder sb) {
        // Improvements:
        // Load averages could be calculated from avenrun_1min (etc) / FSCALE
        // but FSCALE is only defined in sys/param.h and I don't want to reach for JNI yet
        // node_procs_running isn't entirely accurate; it's the total number of processes
        // We can't tell at this stage how many of these are node_procs_blocked
        try {
            Process p = new ProcessBuilder("kstat", "-m", "unix", "-i", "0", "-n", "system_misc").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, bootTime, nproc;
            bootTime = nproc = "";
            
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length >= 2) {
                    String metricName = parts[0];
                    String metricValue = parts[1];
                    
                    if (metricName.equals("boot_time")) {
                        bootTime = metricValue;
                    } else if (metricName.equals("nproc")) {
                        nproc = metricValue;
                    }
                }
            }
            reader.close();
            
            // Output node_boot_time_seconds metric
            if (bootTime != "") {
                sb.append("# HELP node_boot_time_seconds Node boot time, in seconds since Unix epoch.\n");
                sb.append("# TYPE node_boot_time_seconds gauge\n");
                sb.append("node_boot_time_seconds ").append(bootTime).append("\n");
            }
            
            // Output node_procs_running metric
            if (nproc != "") {
                sb.append("# HELP node_procs_running Number of processes in runnable state.\n");
                sb.append("# TYPE node_procs_running gauge\n");
                sb.append("node_procs_running ").append(nproc).append("\n");
            }
        } catch (IOException e) {
            // TODO exception handling
            sb.append("# Error reading system_misc metrics: ").append(e.getMessage()).append("\n");
        }
    }

    private static void scrapeLoadAvg(StringBuilder sb) {
        try {
            Process p = new ProcessBuilder("uptime").start();
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
                // TODO exception handling
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
            // TODO exception handling
            sb.append("# Error reading uptime metrics: ").append(e.getMessage()).append("\n");
        }
    }
}
