.PHONY: run

run:
	export $$(grep -v '^#' .env.local | xargs) && mvn spring-boot:run
