package se.kth.infosys.tomcat;

/*
 * Copyright (C) 2014 KTH, Kungliga tekniska hogskolan, http://www.kth.se
 *
 * This file is part of tomcat-couchbase-persistence.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.Session;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.session.StoreBase;

import com.couchbase.client.CouchbaseClient;
import com.couchbase.client.protocol.views.DesignDocument;
import com.couchbase.client.protocol.views.InvalidViewException;
import com.couchbase.client.protocol.views.Query;
import com.couchbase.client.protocol.views.View;
import com.couchbase.client.protocol.views.ViewDesign;
import com.couchbase.client.protocol.views.ViewResponse;
import com.couchbase.client.protocol.views.ViewRow;

/**
 * This is an implementation of a Apache Tomcat PersistentManager Store that
 * uses a Couchbase bucket to store sessions in.
 * 
 * The PersistentManager only writes idle sessions to the storage back-end, so
 * it is not a fail-over high-availability solution. However, the manager writes
 * all sessions to the back-end on shutdown, so it can be used to migrate 
 * sticky sessions to another server on system maintenance. 
 */
public class CouchbasePersistentStore extends StoreBase {
    /* URIs to use to connect to Couchbase servers. */
    private CouchbaseClient couchbaseClient;

    /* URIs to use to connect to Couchbase servers. */
    private List<URI> uris = new ArrayList<URI>();

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
    	return this.getClass().getSimpleName() + "/0.0.0";
    }


    /**
     * {@inheritDoc}
     */
    public void clear() throws IOException {
    	manager.getContainer().getLogger().debug("Clearing all sessions");
        for (String id : keys()) {
            couchbaseClient.delete(id);
        }
    }


    /**
     * {@inheritDoc}
     */
    public int getSize() throws IOException {
    	manager.getContainer().getLogger().debug("Get number of sessions.");
        Query query = new Query();
        query.setIncludeDocs(false);
        query.setReduce(true);

        View view = couchbaseClient.getView(SESSION_DOCUMENT, ALL_SESSIONS.getName());
        ViewResponse response = (ViewResponse) couchbaseClient.query(view, query);
        Iterator<ViewRow> iterator = response.iterator();
        if (iterator.hasNext()) {
            ViewRow res = response.iterator().next();
            return Integer.valueOf(res.getValue());
        } else {
            return 0;
        }
    }


    /**
     * {@inheritDoc}
     */
    public String[] keys() throws IOException {
    	manager.getContainer().getLogger().debug("Get all keys for sessions");
        Query query = new Query();
        query.setIncludeDocs(false);
        query.setReduce(false);

        View view = couchbaseClient.getView(SESSION_DOCUMENT, ALL_SESSIONS.getName());
        ViewResponse response = (ViewResponse) couchbaseClient.query(view, query);
        Iterator<ViewRow> iterator = response.iterator();
        ArrayList<String> allKeys = new ArrayList<String>();
        while (iterator.hasNext()) {
        	allKeys.add(iterator.next().getKey());
        }
        return allKeys.toArray(new String[allKeys.size()]);
    }


    /**
     * {@inheritDoc}
     */
    public Session load(String sessionId) throws ClassNotFoundException, IOException {
    	manager.getContainer().getLogger().debug("Get session with id: " + sessionId);

    	String res = (String) couchbaseClient.get(sessionId);
    	if (res == null) {
    		return null;
    	}
    	
    	StandardSession session = (StandardSession) manager.createEmptySession();
    	InputStream is = new ByteArrayInputStream(res.getBytes());
    	ObjectInputStream ois = new ObjectInputStream(is);
    	session.readObjectData(ois);
    	session.setManager(manager);
    	return session;
    }


    /**
     * {@inheritDoc}
     */
    public void remove(String sessionId) throws IOException {
    	manager.getContainer().getLogger().debug("Remove session with id: " + sessionId);
        couchbaseClient.delete(sessionId);
    }


    /**
     * {@inheritDoc}
     */
    public void save(Session session) throws IOException {
    	manager.getContainer().getLogger().debug("Save session with id: " + session.getId());

    	ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(bos));
        ((StandardSession) session).writeObjectData(oos);
        oos.close();
        couchbaseClient.set(session.getId(), bos.toString());
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
    	manager.getContainer().getLogger().info("Trying to connect to couchbase bucket " + bucket);
        try {
			this.couchbaseClient = new CouchbaseClient(uris, bucket, username, password);
		} catch (IOException e) {
			throw new LifecycleException(e);
		}
        doEnsureIndexes(SESSION_DOCUMENT, ALL_VIEWS);
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
    	manager.getContainer().getLogger().info("Shutting down.");
        super.stopInternal();
        if (couchbaseClient != null) {
            couchbaseClient.shutdown();
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
    	uris.clear();
        for (String uriStr : uriStrings.split(",")) {
            uris.add(URI.create(uriStr.trim()));
        }
    }


    /**
     * Ensures that all views exists in the database.
     * 
     * @param documentName the name of the design document.
     * @param views the views to ensure exists in the database.
     */
    private void doEnsureIndexes(final String documentName, final List<ViewDesign> views) {
        DesignDocument document;
        try {
            document = couchbaseClient.getDesignDoc(documentName);
            List<ViewDesign> oldViews = document.getViews();

            for (ViewDesign view : views) {
                if (!isViewInList(view, oldViews)) {
                    throw new InvalidViewException("Missing view: " + view.getName());
                }
            }
            manager.getContainer().getLogger().info(
            		"All views are already created for bucket " + bucket);
        } catch (final InvalidViewException e) {
        	manager.getContainer().getLogger().warn(
        			"Missing indexes in database for document " + documentName + ", creating new.");
            document = new DesignDocument(documentName);
            for (ViewDesign view : views) {
                document.getViews().add(view);
                if (!couchbaseClient.createDesignDoc(document)) {
                    throw new InvalidViewException("Failed to create views.");
                }
            }
        }
    }
    
    
    /**
     * @param needle the view design to look for
     * @param stack the list of view designs to look in
     * @return true if needle exists in stack
     */
    private static boolean isViewInList(final ViewDesign needle, final List<ViewDesign> stack) {
        for (ViewDesign view : stack) {
            if (equals(needle, view)) {
                return true;
            }
        }
        return false;
    }


    /**
     * @param d1 a view design.
     * @param d2 another view design.
     * @return true if designs are equal.
     */
    private static boolean equals(final ViewDesign d1, final ViewDesign d2) {
        return (d1.getName().equals(d2.getName())
                && d1.getMap().equals(d2.getMap())
                && d1.getReduce().equals(d2.getReduce()));
    }


    /*
     * Views, or indexes, in the database. 
     */
    private static final ViewDesign ALL_SESSIONS = new ViewDesign(
            "all_sessions", 
            "function(d,m) {emit(m.id);}",
            "_count");
    private static final List<ViewDesign> ALL_VIEWS = Arrays.asList(new ViewDesign[] {
            ALL_SESSIONS
    });
    private static final String SESSION_DOCUMENT = "sessions";
}
