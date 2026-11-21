ARG GRAALVM_IMAGE=ghcr.io/graalvm/jdk-community:25i4-25.0.4.1.1-ol9

FROM ${GRAALVM_IMAGE} AS build

RUN microdnf install -y maven \
    && microdnf clean all

WORKDIR /src
COPY pom.xml LICENSE ./
COPY jvk-api/ ./jvk-api/
COPY core/ ./core/
COPY guest-bootstrap/ ./guest-bootstrap/
COPY jbox/ ./jbox/
COPY espresso/ ./espresso/
COPY runtime/ ./runtime/
RUN mvn -B -ntp package

FROM ${GRAALVM_IMAGE} AS runtime

RUN useradd --create-home --uid 1000 --user-group espressh \
    && mkdir -p /opt/espressh /workspace \
    && chown espressh:espressh /workspace

COPY --from=build /src/runtime/target/espressh.jar /opt/espressh/espressh.jar
COPY LICENSE /opt/espressh/LICENSE-MIT
COPY espresso/espresso/LICENSE /opt/espressh/LICENSE-GPL-2.0

ENV HOME=/home/espressh
USER espressh
WORKDIR /workspace
ENTRYPOINT ["java", "-jar", "/opt/espressh/espressh.jar"]
