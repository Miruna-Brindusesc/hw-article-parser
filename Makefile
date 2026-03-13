
MAVEN = mvn -q
JAVA = java
JAR = target/tema1-1.0-jar-with-dependencies.jar

.PHONY: build run clean test_small

build:
	$(MAVEN) clean compile assembly:single

run:
	$(JAVA) -jar $(JAR) $(ARGS)

clean:
	$(MAVEN) clean
	rm -f *.txt

test_small:
	make run ARGS="4 ../checker/input/tests/test_small/articles.txt ../checker/input/tests/test_small/inputs.txt"