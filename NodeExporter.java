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
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class NodeExporter {
    private static final SimpleDateFormat apache = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z");

    public static void main(String[] args) {
        ServerSocket serverSocket = null;
        Socket clientSocket = null;
        try {
            serverSocket = new ServerSocket(9100);
        } catch (IOException e) {
            System.err.println("Error creating socket: " + e.getMessage());
            System.exit(1);
        }
        System.err.println("NodeExporter started on port 9100");
        while (true) {
            try {
                clientSocket = serverSocket.accept();
                handle(clientSocket);
            } catch (IOException e) {
                System.err.println("Error handling client: " + e.getMessage());
            } finally {
                if (clientSocket != null) {
                    try {
                        clientSocket.close();
                    } catch (IOException e) {
                        System.err.println("Error closing socket: " + e.getMessage());
                    }
                }
            }
        }
    }

    public static void handle(Socket socket) throws IOException {
        // Parse the HTTP request
        String requestLine, line, method, path, referer, userAgent, client;
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        // NOTE: auto-flushing is enabled-- we should check to make sure this isn't causing weird behavior once running under load
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        // First line of the request should be the method, path, and protocol
        requestLine = in.readLine();
        if (requestLine == null) {
            return;
        }
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 3) {
            return;
        }
        method = parts[0];
        path = parts[1];

        // This server only supports GET /metrics; give a 404 otherwise
        if (!method.equals("GET") || !path.equals("/metrics")) {
            out.print("HTTP/1.1 404 NOT FOUND\r\n");
            out.print("Content-Type: text/plain; charset=utf-8\r\n");
            out.print("Content-Length: 2\r\n");
            out.print("\r\n");
            // TODO something in here is broken
            System.out.println("404!!");
            return;
        }

        // Read the headers we need if we're on the right path and method
        referer = "-";
        userAgent = "-";
        while ((line = in.readLine()) != null && line.length() > 0) {
            if (line.toLowerCase().startsWith("referer:")) {
                referer = line.substring(8).trim();
            } else if (line.toLowerCase().startsWith("user-agent:")) {
                userAgent = line.substring(11).trim();
            }
        }

        StringBuilder response = runScrapers();

        out.print("HTTP/1.1 200 OK\r\n");
        out.print("Content-Type: text/plain; charset=utf-8\r\n");
        out.print("Content-Length: " + response.length() + "\r\n");
        out.print("\r\n");
        out.print(response.toString());
        out.close();

        // Emit web server log in Apache combined log format
        InetAddress remoteAddr = socket.getInetAddress();
        client = remoteAddr != null ? remoteAddr.getHostAddress() : "-";
        String timestamp = apache.format(new Date());
        System.out.printf("%s - - [%s] \"%s\" %d %d \"%s\" \"%s\"%n", client, timestamp, requestLine, 200, response.length(), referer, userAgent);
    }

    public static StringBuilder runScrapers() {
        StringBuilder response = new StringBuilder();
        // establish collector metrics
        long start;
        boolean succeeded;
        StringBuilder timing = new StringBuilder();
        StringBuilder success = new StringBuilder();

        // call all collectors
        // loadavg
        start = System.nanoTime();
        succeeded = scrapeLoadAvg(response);
        collectStats("loadavg", timing, success, start, System.nanoTime(), succeeded);

        // boottime & procs_running
        start = System.nanoTime();
        succeeded = scrapeSystemMisc(response);
        collectStats("os", timing, success, start, System.nanoTime(), succeeded);

        // arp counters
        start = System.nanoTime();
        succeeded = scrapeArp(response);
        collectStats("arp", timing, success, start, System.nanoTime(), succeeded);

        // interrupts, context switches, forks
        start = System.nanoTime();
        succeeded = scrapeVmstatTotals(response);
        collectStats("vmstat", timing, success, start, System.nanoTime(), succeeded);

        // emit collector duration & success metrics
        response.append("# HELP node_scrape_collector_duration_seconds node_exporter: Duration of a collector scrape.\n");
        response.append("# TYPE node_scrape_collector_duration_seconds gauge\n");
        response.append(timing.toString());
        response.append("# HELP node_scrape_collector_success node_exporter: Whether a collector succeeded.\n");
        response.append("# TYPE node_scrape_collector_success gauge\n");
        response.append(success.toString());
        return response;
    }

    private static void collectStats(String collectorName, StringBuilder timing, StringBuilder success, long start, long end, boolean succeeded) {
        double durationSeconds = (end - start) / 1000000000.0;
        timing.append("node_scrape_collector_duration_seconds{collector=\"").append(collectorName).append("\"} ").append(durationSeconds).append("\n");
        success.append("node_scrape_collector_success{collector=\"").append(collectorName).append("\"} ").append(succeeded ? "1" : "0").append("\n");
    }

    private static boolean scrapeVmstatTotals(StringBuilder sb) {
        try {
            Process p = new ProcessBuilder("vmstat", "-s").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, deviceInterrupts, cpuContextSwitches;
            deviceInterrupts = cpuContextSwitches = "";
            int forks, vforks;
            forks = vforks = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.endsWith("device interrupts")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 1) {
                        deviceInterrupts = parts[0];
                    }
                } else if (line.endsWith("cpu context switches")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 1) {
                        cpuContextSwitches = parts[0];
                    }
                // catches both the forks and vforks lines
                } else if (line.endsWith("forks")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 1) {
                        try {
                            forks = forks + Integer.parseInt(parts[0]);
                        } catch (NumberFormatException e) {
                            sb.append("# Error converting forks metric: ").append(line).append("\n");
                            return false;
                        }
                    }
                }
            }
            reader.close();

            // Output the simple interrupt/context switch counters
            sb.append("# HELP node_intr_total Total number of interrupts serviced.\n");
            sb.append("# TYPE node_intr_total counter\n");
            sb.append("node_intr_total ").append(deviceInterrupts).append("\n");
            sb.append("# HELP node_context_switches_total Total number of context switches.\n");
            sb.append("# TYPE node_context_switches_total counter\n");
            sb.append("node_context_switches_total ").append(cpuContextSwitches).append("\n");

            // Output node_forks_total metric (forks + vforks)
            sb.append("# HELP node_forks_total Total number of forks.\n");
            sb.append("# TYPE node_forks_total counter\n");
            sb.append("node_forks_total ").append(forks).append("\n");
            return true;
        } catch (IOException e) {
            System.err.println("Error reading static vmstat metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeArp(StringBuilder sb) {
        try {
            Process p = new ProcessBuilder("arp", "-a", "-n").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            HashMap<String, Integer> deviceCounts = new HashMap<String, Integer>();

            String line;
            while ((line = reader.readLine()) != null) {
                // Skip header lines
                if ((line.contains("Device") && line.contains("IP Address")) || (line.contains("------")) || (line.contains("Net to Media Table"))) {
                    continue;
                }
                line = line.trim();
                if (line.length() == 0) {
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
                return false;
            } else {
                sb.append("# HELP node_arp_entries Number of ARP entries for each device.\n");
                sb.append("# TYPE node_arp_entries gauge\n");
                for (Map.Entry<String, Integer> entry : deviceCounts.entrySet()) {
                    sb.append(String.format("node_arp_entries{device=\"%s\"} %d\n", entry.getKey(), entry.getValue()));
                }
                return true;
            }
        } catch (IOException e) {
            System.err.println("Error reading arp metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeSystemMisc(StringBuilder sb) {
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

            boolean success = true;
            // Output node_boot_time_seconds metric
            if (!bootTime.equals("")) {
                sb.append("# HELP node_boot_time_seconds Node boot time, in seconds since Unix epoch.\n");
                sb.append("# TYPE node_boot_time_seconds gauge\n");
                sb.append("node_boot_time_seconds ").append(bootTime).append("\n");
            } else {
                sb.append("# Error retrieving node_boot_time_seconds metric\n");
                success = false;
            }

            // Output node_procs_running metric
            if (!nproc.equals("")) {
                sb.append("# HELP node_procs_running Number of processes in runnable state.\n");
                sb.append("# TYPE node_procs_running gauge\n");
                sb.append("node_procs_running ").append(nproc).append("\n");
            } else {
                sb.append("# Error retrieving node_procs_running metric\n");
                success = false;
            }
            return success;
        } catch (IOException e) {
            System.err.println("Error reading system_misc metrics: " + e.getMessage());
            return false;
        }
    }

    private static boolean scrapeLoadAvg(StringBuilder sb) {
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

            if (one.equals("") || five.equals("") || fifteen.equals("")) {
                sb.append("# Error parsing uptime metrics: ").append(line).append("\n");
                return false;
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
            return true;
        } catch (IOException e) {
            System.err.println("Error reading uptime metrics: " + e.getMessage());
            return false;
        }
    }
}
