tomcat-couchbase-persistence
============================

Persistence manager back-end to store Tomcat sessions in Couchbase.

First attempt here is to store sessions using the included PersistentManager
in Apache Tomcat, and just write a `Store` to put the sessions in Couchbase.

This kind of works, but the high-availability support of the PersistentManager
is rather limited. It "swaps" out inactive sessions to the backend, freeing
up memory, and writes all sessions to the store on shutdown. This makes
it possible to move sessions to another server for, e.g., maintenance purposes.
It does not handle a system crash.

You need to add the jar built by this project, as well as the runtime jars of
the Couchbase client (as of this writing available here:
http://packages.couchbase.com/clients/java/1.4.1/Couchbase-Java-Client-1.4.1.zip),
into the $CATALINA_BASE/lib directory. After that you need to add a Manager
configuration to use the CouchbasePersistentStore. This can be done in several
ways. Setting a default Manager that all webapps will pick up can be done
in context.xml.

$CATALINA_BASE/conf/context.xml
```
    <Manager className="org.apache.catalina.session.PersistentManager"
             maxIdleBackup="1"
             maxIdleSwap="1"
             >
      <Store className="se.kth.infosys.tomcat7.CouchbasePersistentStore"
             uris="http://localhost:8091/pools"
             bucket="TomcatSessions"
             username=""
             password=""/>
    </Manager>
```