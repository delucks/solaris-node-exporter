# NodeExporter

There'll be a description here instead of notes someday.

vmstat

```
$ vmstat
 kthr      memory            page            disk          faults      cpu
 r b w   swap  free  re  mf pi po fr de sr s0 s1 s2 s3   in   sy   cs us sy id
 0 0 0 31572624 31678096 229 829 0 0 0 0 47 -0 -0 2  2  424 1513  321  1  1 99
```

node_memory_SwapTotal_bytes -> swap
node_disk_io_now -> disk (ops/second... good enough proxy?)

node_intr_total, node_context_switches_total, node_cpu_seconds_total can't be done by this because "in" is per second

mpstat

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
node_cpu_seconds_total
