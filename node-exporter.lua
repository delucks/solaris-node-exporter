#!/usr/bin/env lua

-- Prometheus node_exporter implementation for Solaris
-- Written in lua because it's the easiest damn programming language to compile
-- If this results in unacceptable runtime or resource consumption we'll have to write in JAVA or something :)

-- Forked from the openwrt_exporter codebase, credited here:
-- https://github.com/jschornick/openwrt_exporter
-- Copyright (c) 2026 Jamie Luck
-- Copyright (c) 2016 Jeff Schornick <jeff@schornick.org>
-- Copyright (c) 2015 Kevin Lyda
-- Licensed under the Apache License, Version 2.0

socket = require("socket")

-- This table defines the scrapers to run.
-- Each corresponds directly to a scraper_<name> function.
SCRAPERS = { "load_averages" } 

SERVERNAME = "lua-node-exporter"

-- Parsing

function space_split(s)
  elements = {}
  for element in s:gmatch("%S+") do
    table.insert(elements, element)
  end
  return elements
end

function line_split(s)
  elements = {}
  for element in s:gmatch("[^\n]+") do
    table.insert(elements, element)
  end
  return elements
end

function get_contents(filename)
  local f = io.open(filename, "rb")
  local contents = ""
  if f then
    contents = f:read "*a"
    f:close()
  end

  return contents
end

function popen(command)
  local handle = io.popen(command)
  local result = handle:read "*a"

  return result
end

-- Metric printing

function print_metric(metric, labels, value)
  local label_string = ""
  if labels then
    for label,value in pairs(labels) do
      label_string =  label_string .. label .. '="' .. value .. '",'
    end
    label_string = "{" .. string.sub(label_string, 1, -2) .. "}"
  end
  output(string.format("%s%s %s", metric, label_string, value))
end

function metric(name, mtype, labels, value)
  output("# TYPE " .. name .. " " .. mtype)
  local outputter = function(labels, value)
    print_metric(name, labels, value)
  end
  if value then
    outputter(labels, value)
  end
  return outputter
end

-- Scrapers

function scraper_load_averages()
  local loadavg = popen("uptime")
  if loadavg == "" then
    return
  end
  _, _, one, five, fifteen = string.find(loadavg, "load average: (%d.%d%d), (%d.%d%d), (%d.%d%d)")
  metric("node_load1", "gauge", nil, one)
  metric("node_load5", "gauge", nil, five)
  metric("node_load15", "gauge", nil, fifteen)
end

-- Timing and running scrapers

function timed_scrape(scraper)
  local start_time = socket.gettime()
  -- build the function name and call it from global variable table
  _G["scraper_"..scraper]()
  local duration = socket.gettime() - start_time
  return duration
end

function run_all_scrapers()
  times = {}
  for i,scraper in ipairs(SCRAPERS) do
    runtime = timed_scrape(scraper)
    times[scraper] = runtime
    scrape_time_sums[scraper] = scrape_time_sums[scraper] + runtime
    scrape_counts[scraper] = scrape_counts[scraper] + 1
  end

  local name = "node_exporter_scrape_duration_seconds"
  local duration_metric = metric(name, "summary")
  for i,scraper in ipairs(SCRAPERS) do
    local labels = {collector=scraper, result="success"} 
    duration_metric(labels, times[scraper])
    print_metric(name.."_sum", labels, scrape_time_sums[scraper])
    print_metric(name.."_count", labels, scrape_counts[scraper])
  end
end

-- Web server-specific functions

function http_ok_header()
  output("HTTP/1.1 200 OK\r")
  output("Server: " .. SERVERNAME .. "\r")
  output("Content-Type: text/plain\r")
  output("\r")
end

function http_not_found()
  output("HTTP/1.1 404 Not Found\r")
  output("Server: " .. SERVERNAME .. "\r")
  output("Content-Type: text/plain\r")
  output("\r")
  output("ERROR: File Not Found.")
end

function serve(request)
  if not string.match(request, "GET /metrics.*") then
    http_not_found()
  else
    http_ok_header()
    run_all_scrapers()
  end
  client:close()
  return true
end

-- Main program

for k,v in ipairs(arg) do
  if (v == "-p") or (v == "--port") then
    port = arg[k+1]
  end
end

scrape_counts = {}
scrape_time_sums = {}
for i,scraper in ipairs(SCRAPERS) do
  scrape_counts[scraper] = 0
  scrape_time_sums[scraper] = 0
end

if port then
  server = assert(socket.bind("*", port))

  while 1 do
    client = server:accept()
    client:settimeout(60)
    local request, err = client:receive()

    if not err then
      output = function (str) client:send(str.."\n") end
      if not serve(request) then
        break
      end
    end
  end
else
  output = print
  run_all_scrapers()
end
