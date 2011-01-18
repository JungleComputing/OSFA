BASEDIR=/tmp/basedir

b1:
	./sleep60x1.py

b2:
	./sleep1x60.py

b3:
	mkdir $(BASEDIR)
	./namespace1.py
	rmdir $(BASEDIR)

b4:
	mkdir $(BASEDIR)
	./file1.py
	rmdir $(BASEDIR)
