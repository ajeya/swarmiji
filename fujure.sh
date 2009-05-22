#!/bin/sh

# Please make sure to configure ~/.clojure.conf or /etc/clojure.conf
#  sample configuration can be found at clojure.conf.sample
#
# Note, running this script will:
#   - Run ~/.clojurerc on boot up (if exists)
#   - Add all .jar files within clj_ext (~/.clojure on default)
#     to the classpath
#
#

if [ ! -f /etc/clojure.conf -a ! -f /mnt/furtive/clojure.conf -a ! -f ~/.clojure.conf  ]; then
    echo "Error: No config not found at /etc/clojure.conf or ~/.clojure.conf."
    echo "  Please provide one before starting this script."
    echo "  A sample can be found in the emacs-clojure repository named "
    echo "   clojure.conf.sample"
    exit
fi


# Whether to load the repl or script
if [ -z "$1" ]; then
	clj_class=clojure.lang.Repl
else
	clj_class=clojure.lang.Script
fi

echo "SWARMIJI_HOME is ${SWARMIJI_HOME}"
echo "SWARMIJI_ENV is ${SWARMIJI_ENV}"

swarmiji_jars="${SWARMIJI_HOME}/lib/java"
swarmiji_clj="${SWARMIJI_HOME}/src/:${SWARMIJI_HOME}/lib/clojure/clj-record/src/"

clj_cp="."
[ -f /etc/clojure.conf ] && . /etc/clojure.conf
[ -f /mnt/furtive/clojure.conf ] && . /mnt/furtive/clojure.conf
[ -f ~/.clojure.conf ]   && . ~/.clojure.conf
[ -f ~/.clojurerc ] && clj_rc=~/.clojurerc
clj_cp="${clj_cp}:${swarmiji_jars}/*:${swarmiji_clj}/:${clj_ext}/*"

if [ -n "${clj_lib}" ]; then
    export LD_LIBRARY_PATH=${clj_lib}:$LD_LIBRARY_PATH
fi

echo exec java -Xms256m -Xmx512m -server -Dpid=$$ ${clj_opts} -cp ${clj_cp}:${clj} ${clj_wrapper} ${clj_class} ${clj_rc} $*
exec java -Xms256m -Xmx512m -server -Dpid=$$ ${clj_opts} -cp ${clj_cp}:${clj} ${clj_wrapper} ${clj_class} ${clj_rc} $1 -- $*
