BASEDIR=/tmp/basedir

sleep1x60:
	~/bin/jysaga saga_job_benchmark.py any://localhost 5 60 sleep 1

sleep60x1:
	~/bin/jysaga saga_job_benchmark.py any://localhost 5 1 sleep 60

namespace:
	mkdir $(BASEDIR)
	~/bin/jysaga saga_namespace_benchmark.py local://localhost$(BASEDIR) 5
	rmdir $(BASEDIR)
file:
	mkdir $(BASEDIR)
	~/bin/jysaga saga_file_benchmark.py local://localhost$(BASEDIR) 5
	rmdir $(BASEDIR)
