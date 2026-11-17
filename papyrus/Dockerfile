# SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
# SPDX-License-Identifier: Apache-2.0

ARG BASE_IMAGE=base
FROM $BASE_IMAGE
ENV DEBIAN_FRONTEND=noninteractive

SHELL ["/bin/bash", "-euo", "pipefail", "-c"]
ENV SHELL=/bin/bash

ARG ECLIPSE_REPOSITORY=https://download.eclipse.org

USER root

ARG PAPYRUS_VERSION
ADD ./versions/${PAPYRUS_VERSION}/papyrus.tar.gz /opt/

WORKDIR /opt/

# Runtime libraries for headless rendering (diagram cache) via xvfb-run
# and x2polarion (cairosvg, python-magic)
RUN apt-get update && \
    apt-get install --no-install-recommends -y \
    libgtk-3-0 \
    libxtst6 \
    xvfb \
    xauth \
    dbus-x11 \
    fontconfig \
    libcairo2 \
    libmagic1 \
    fonts-dejavu-core && \
    rm -rf /var/lib/apt/lists/*

# Papyrus lays out diagram text in Segoe UI (see fontconfig/30-segoe-ui.conf).
# Selawik, its metric compatible replacement, keeps label widths, wrapping
# and truncation as on Windows.
COPY fontconfig/30-segoe-ui.conf /etc/fonts/conf.d/30-segoe-ui.conf
COPY fonts/selawk.ttf fonts/selawkb.ttf fonts/LICENSE.txt /usr/local/share/fonts/selawik/
RUN fc-cache -f

# x2polarion (Polarion sync of the diagram cache) needs Python 3.13; the
# base image ships 3.11. Installed for all users, used via
# `uv venv --python 3.13`.
ENV UV_PYTHON_INSTALL_DIR=/opt/uv/python
RUN pip install --no-cache-dir uv && \
    uv --no-cache python install 3.13 && \
    chmod -R a+rX /opt/uv

COPY set_memory_flags.py /opt/setup/set_memory_flags.py
# Papyrus 7.x ships the launcher as papyrus-desktop; expose it as papyrus
RUN if [ -f Papyrus/papyrus-desktop ]; then \
    ln -s papyrus-desktop Papyrus/papyrus && \
    ln -s papyrus-desktop.ini Papyrus/papyrus.ini && \
    chmod +x Papyrus/papyrus-desktop && \
    chown techuser Papyrus/papyrus-desktop.ini && \
    chown -h techuser Papyrus/papyrus.ini; \
    fi; \
    mkdir -p /workspace; \
    # Set workspace permissions
    chown -R techuser /workspace && \
    chmod +x Papyrus/papyrus && \
    chown techuser /opt/Papyrus/papyrus.ini

# Install SysML
RUN case "$PAPYRUS_VERSION" in \
    6.*) SYSML_REPOSITORY=${ECLIPSE_REPOSITORY}/modeling/mdt/papyrus/components/sysml16/releases/2.2.0_c/p2/ ;; \
    7.*) SYSML_REPOSITORY=${ECLIPSE_REPOSITORY}/modeling/mdt/papyrus/papyrus-desktop/components/sysml16/releases/2.4.0/p2/ ;; \
    *) echo "Unsupported PAPYRUS_VERSION $PAPYRUS_VERSION"; exit 1 ;; \
    esac; \
    /opt/Papyrus/papyrus \
    -consoleLog \
    -application org.eclipse.equinox.p2.director \
    -noSplash \
    -repository "$SYSML_REPOSITORY" \
    -installIU "org.eclipse.papyrus.sysml16.elementgroup.feature.feature.group,org.eclipse.papyrus.sysml16.feature.feature.group,org.eclipse.papyrus.sysml16.requirements.extension.feature.feature.group"

ENV ECLIPSE_INSTALLATION_PATH=/opt/Papyrus
ENV ECLIPSE_EXECUTABLE=/opt/Papyrus/papyrus

# Install dropins for Papyrus
COPY ./versions/${PAPYRUS_VERSION}/dropins /opt/Papyrus/dropins
ARG PAPYRUS_DROPINS=""
COPY install_dropins.py /opt/install_dropins.py
COPY ./versions/${PAPYRUS_VERSION}/dropins*.yml /opt
COPY ./versions/${PAPYRUS_VERSION}/patches /opt/patches
RUN DROPINS="$PAPYRUS_DROPINS" python /opt/install_dropins.py

# Headless diagram cache exporter (Papyrus 7.x only)
COPY diagram-cache /opt/setup/diagram-cache
RUN case "$PAPYRUS_VERSION" in \
    7.*) bash /opt/setup/diagram-cache/build.sh /opt/Papyrus && \
    install -m 0755 /opt/setup/diagram-cache/papyrus_diagram_cache.py /usr/local/bin/papyrus-diagram-cache ;; \
    esac

COPY ./autostart /home/techuser/.config/openbox/autostart

ENV AUTOSTART_PAPYRUS=1
ENV RESTART_PAPYRUS=1

# Set memory options for the JVM (used by set_memory_flags.py)
ARG MEMORY_MAX=90%
ENV MEMORY_MAX=${MEMORY_MAX}
ARG MEMORY_MIN=70%
ENV MEMORY_MIN=${MEMORY_MIN}

ENV BASE_TYPE=papyrus
ENV DISPLAY_NAME="Papyrus $PAPYRUS_VERSION"

USER techuser
