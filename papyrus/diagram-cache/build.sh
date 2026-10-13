#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright DB InfraGO AG and contributors
# SPDX-License-Identifier: Apache-2.0

# Compile the diagram cache exporter bundle against a Papyrus installation
# and register it in that installation.
#
# Usage: build.sh <papyrus-install-dir>
set -euo pipefail

INSTALL_DIR="${1:?usage: build.sh <papyrus-install-dir>}"
PLUGINS="${INSTALL_DIR}/plugins"
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/plugin" && pwd)"
BUNDLE=org.dbinfrago.papyrus.diagramcache
VERSION=1.0.0
JAR_NAME="${BUNDLE}_${VERSION}.jar"

JDK_BIN="$(find -L "${PLUGINS}" -maxdepth 3 -type d -path '*/org.eclipse.justj.openjdk.hotspot.jre.full.*/jre/bin' | head -1)"
if [[ -z "${JDK_BIN}" ]]; then
	echo "No JustJ JRE found in ${PLUGINS}" >&2
	exit 1
fi

PREFIXES=(
	org.eclipse.core.runtime_ org.eclipse.core.resources_ org.eclipse.core.jobs_
	org.eclipse.equinox.app_ org.eclipse.equinox.common_ org.eclipse.equinox.registry_
	org.eclipse.osgi_ org.eclipse.emf.common_ org.eclipse.emf.ecore_
	org.eclipse.emf.ecore.xmi_ org.eclipse.emf.edit_ org.eclipse.emf.transaction_
	org.eclipse.sirius_ org.eclipse.sirius.ui_ org.eclipse.sirius.diagram_
	org.eclipse.sirius.diagram.ui_ org.eclipse.sirius.common_
	org.eclipse.sirius.model_ org.eclipse.sirius.diagram.model_ com.google.gson_
	org.eclipse.gmf.runtime.notation_ org.eclipse.gmf.runtime.diagram.core_
	org.eclipse.gmf.runtime.diagram.ui_ org.eclipse.gmf.runtime.diagram.ui.render_
	org.eclipse.papyrus.infra.core_ org.eclipse.papyrus.infra.gmfdiag.common_
	org.eclipse.gef_ org.eclipse.draw2d_
	org.eclipse.ui.workbench_ org.eclipse.jface_ org.eclipse.uml2.uml_
	org.eclipse.gmf.runtime.common.core_ org.eclipse.gmf.runtime.common.ui.services_
	org.eclipse.gmf.runtime.draw2d.ui_ org.eclipse.gmf.runtime.gef.ui_
)
CLASSPATH=""
for prefix in "${PREFIXES[@]}"; do
	jar="$(find "${PLUGINS}" -maxdepth 1 -name "${prefix}*.jar" ! -name '*.source_*' | head -1)"
	if [[ -z "${jar}" ]]; then
		echo "No bundle matching ${prefix}*.jar in ${PLUGINS}" >&2
		exit 1
	fi
	CLASSPATH="${CLASSPATH:+${CLASSPATH}:}${jar}"
done
# SWT classes live in the platform fragment (e.g. org.eclipse.swt.gtk.linux.x86_64)
swt="$(find "${PLUGINS}" -maxdepth 1 \( -name 'org.eclipse.swt.gtk.*.jar' -o -name 'org.eclipse.swt.cocoa.*.jar' -o -name 'org.eclipse.swt.win32.*.jar' \) ! -name '*.source_*' | head -1)"
if [[ -z "${swt}" ]]; then
	echo "No SWT platform fragment in ${PLUGINS}" >&2
	exit 1
fi
CLASSPATH="${CLASSPATH}:${swt}"

BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "${BUILD_DIR}"' EXIT
mkdir "${BUILD_DIR}/classes"
find "${SRC_DIR}/src" -name '*.java' -print0 |
	xargs -0 "${JDK_BIN}/javac" --release 21 -cp "${CLASSPATH}" -d "${BUILD_DIR}/classes"
cp "${SRC_DIR}/plugin.xml" "${BUILD_DIR}/classes/plugin.xml"
"${JDK_BIN}/jar" cmf "${SRC_DIR}/META-INF/MANIFEST.MF" "${BUILD_DIR}/${JAR_NAME}" -C "${BUILD_DIR}/classes" .

cp "${BUILD_DIR}/${JAR_NAME}" "${PLUGINS}/${JAR_NAME}"
BUNDLES_INFO="${INSTALL_DIR}/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
if ! grep -q "^${BUNDLE}," "${BUNDLES_INFO}"; then
	echo "${BUNDLE},${VERSION},plugins/${JAR_NAME},4,false" >>"${BUNDLES_INFO}"
fi
