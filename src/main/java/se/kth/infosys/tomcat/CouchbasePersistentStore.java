package se.kth.infosys.tomcat;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.Session;
import org.apache.catalina.session.StoreBase;

import com.couchbase.client.protocol.views.Query;
import com.couchbase.client.protocol.views.View;
import com.couchbase.client.protocol.views.ViewDesign;
import com.couchbase.client.protocol.views.ViewResponse;

public class CouchbasePersistentStore extends StoreBase {
    private static final Logger logger =  Logger.getLogger(CouchbaseClientFactory.class.getName());
    private CouchbaseClientFactory couchbase;

    private List<URI> uris;

    /* The name of the bucket, will use the default bucket unless otherwise specified. */
    private String bucket = "default";

    /* NOTE: username is not used in Couchbase 2.0, may be in the future. */
    private String username = "";

    /* Password for the bucket if any. */
    private String password = "";

    /**
     * {@inheritDoc}
     */
    public String getInfo() {
        return CouchbaseClientFactory.class.getSimpleName() + "/0.0.0";
    }

    /**
     * {@inheritDoc}
     */
    public void clear() throws IOException {
        for (String id : keys()) {
            couchbase.getClient().delete(id);
        }
    }

    /**
     * {@inheritDoc}
     */
    public int getSize() throws IOException {
        Query query = new Query();
        query.setIncludeDocs(false);
        query.setReduce(false);

        View view = couchbase.getClient().getView(UTIL_DOCUMENT, ALL_SESSIONS.getName());
        ViewResponse response = (ViewResponse) couchbase.getClient().query(view, query);
        return (int) response.getTotalRows();
    }

    /**
     * {@inheritDoc}
     */
    public String[] keys() throws IOException {
        Query query = new Query();
        query.setIncludeDocs(false);
        query.setReduce(false);

        View view = couchbase.getClient().getView(UTIL_DOCUMENT, ALL_SESSIONS.getName());
        ViewResponse response = (ViewResponse) couchbase.getClient().query(view, query);
        Map<String, Object> map = response.getMap();
        return map.keySet().toArray(new String[map.size()]);
    }

    /**
     * {@inheritDoc}
     */
    public Session load(String sessionId) throws ClassNotFoundException, IOException {
        return (Session) couchbase.getClient().get(sessionId);
    }

    /**
     * {@inheritDoc}
     */
    public void remove(String sessionId) throws IOException {
        couchbase.getClient().delete(sessionId);
    }

    /**
     * {@inheritDoc}
     */
    public void save(Session session) throws IOException {
        couchbase.getClient().set(session.getId(), session);
    }

    /**
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {
        this.couchbase = new CouchbaseClientFactory();
        couchbase.setUris(uris);
        couchbase.setBucket(bucket);
        couchbase.setPassword(password);
        couchbase.setUsername(username);
        couchbase.ensureIndexes(UTIL_DOCUMENT, ALL_VIEWS);
        couchbase.initialize();
        super.startInternal();
    }

    /**
     * Stop this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {
        super.stopInternal();
        try {
            couchbase.shutdown();
        } catch (Exception e) {
            logger.warning("Failed to shutdown couchbase: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * @param bucket the bucket to set
     */
    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    /**
     * @param username the username to set
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * @param password the password to set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * @param uris the uris to set
     */
    public void setUris(String uriStrings) {
        for (String uriStr : uriStrings.split(",")) {
            uris.add(URI.create(uriStr.trim()));
        }
    }

    /*
     * Views, or indexes, in the database. 
     */
    private static final ViewDesign ALL_SESSIONS = new ViewDesign(
            "all_tickets", 
            "function(d,m) {emit(m.id);}",
            "_count");
    private static final List<ViewDesign> ALL_VIEWS = Arrays.asList(new ViewDesign[] {
            ALL_SESSIONS
    });
    private static final String UTIL_DOCUMENT = "sessions";
}
