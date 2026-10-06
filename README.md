# NodeExporter

There'll be a description here instead of notes someday.

## vmstat

```
$ vmstat
 kthr      memory            page            disk          faults      cpu
 r b w   swap  free  re  mf pi po fr de sr s0 s1 s2 s3   in   sy   cs us sy id
 0 0 0 31572624 31678096 229 829 0 0 0 0 47 -0 -0 2  2  424 1513  321  1  1 99
```

node_memory_SwapTotal_bytes -> swap
node_disk_io_now -> disk (ops/second... good enough proxy?)

```
$ vmstat -s
        0 swap ins
        0 swap outs
        0 pages swapped in
        0 pages swapped out
   141329 total address trans. faults taken
        3 page ins
        0 page outs
        3 pages paged in
        0 pages paged out
    47750 total reclaims
    47750 reclaims from free list
        0 micro (hat) faults
   141329 minor (as) faults
        3 major faults
    50762 copy-on-write faults
    48036 zero fill page faults
   473084 pages examined by the clock daemon
        0 revolutions of the clock hand
        0 pages freed by the clock daemon
     1020 forks
       11 vforks
      955 execs
  1394255 cpu context switches
  1358689 device interrupts
   197842 traps
  2499110 system calls
   529013 total name lookups (cache hits 96%)
     2231 user   cpu
     5903 system cpu
  1371117 idle   cpu
        0 wait   cpu
```

## mpstat

```
$ mpstat
CPU minf mjf xcal  intr ithr  csw icsw migr smtx  srw syscl  usr sys  wt idl
  0  236   0   81   227    8   81    1    4    6    0   505    1   1   0  98
  1  209   0   84    67   12   74    1    4    7    0   344    1   1   0  99
  2  195   0   59    67   11   83    1    5    5    0   343    0   1   0  99
  3  192   0   61    64    9   79    0    4    7    0   305    0   0   0  99
```

node_intr_total

node_context_switches_total

```
# HELP node_context_switches_total Total number of context switches.
# TYPE node_context_switches_total counter
node_context_switches_total 9.8617107e+07
```

node_cpu_seconds_total

```
# HELP node_cpu_guest_seconds_total Seconds the CPUs spent in guests (VMs) for each mode.
# TYPE node_cpu_guest_seconds_total counter
node_cpu_guest_seconds_total{cpu="0",mode="nice"} 0
node_cpu_guest_seconds_total{cpu="0",mode="user"} 0
# HELP node_cpu_seconds_total Seconds the CPUs spent in each mode.
# TYPE node_cpu_seconds_total counter
node_cpu_seconds_total{cpu="0",mode="idle"} 137278.7
node_cpu_seconds_total{cpu="0",mode="iowait"} 26.56
node_cpu_seconds_total{cpu="0",mode="irq"} 0
node_cpu_seconds_total{cpu="0",mode="nice"} 0.09
node_cpu_seconds_total{cpu="0",mode="softirq"} 99.08
node_cpu_seconds_total{cpu="0",mode="steal"} 28.73
node_cpu_seconds_total{cpu="0",mode="system"} 286.74
node_cpu_seconds_total{cpu="0",mode="user"} 1053.86
```


## Not Possible (with my hardware)

node_cooling_device_cur_state requires either temperature sensors visible to the OS (in the output of `prtdiag -v`) or access to the LOM that keeps that state. On my development machine that's XSCF, which I can't access from the OS.

```
# HELP node_cooling_device_cur_state Current throttle state of the cooling device
# TYPE node_cooling_device_cur_state gauge
node_cooling_device_cur_state{name="0",type="Processor"} 0
# HELP node_cooling_device_max_state Maximum throttle state of the cooling device
# TYPE node_cooling_device_max_state gauge
node_cooling_device_max_state{name="0",type="Processor"} 0
```
