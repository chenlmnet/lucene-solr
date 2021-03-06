/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Socket;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.solr.SolrJettyTestBase;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.util.ExternalPaths;
import org.junit.BeforeClass;
import org.junit.Test;

public class BasicHttpSolrServerTest extends SolrJettyTestBase {
  
  public static class RedirectServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      resp.sendRedirect("/solr/select?" + req.getQueryString());
    }
  }
  
  public static class SlowServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {}
    }
  }
  
  public static class DebugServlet extends HttpServlet {
    public static void clear() {
      lastMethod = null;
      headers = null;
      parameters = null;
    }
    
    public static String lastMethod = null;
    public static HashMap<String,String> headers = null;
    public static Map<String,String[]> parameters = null;
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      lastMethod = "get";
      recordRequest(req, resp);
    }
    
    private void setHeaders(HttpServletRequest req) {
      Enumeration<String> headerNames = req.getHeaderNames();
      headers = new HashMap<String,String>();
      while (headerNames.hasMoreElements()) {
        final String name = headerNames.nextElement();
        headers.put(name, req.getHeader(name));
      }
    }

    private void setParameters(HttpServletRequest req) {
      parameters = req.getParameterMap();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      lastMethod = "post";
      recordRequest(req, resp);
    }
    
    private void recordRequest(HttpServletRequest req, HttpServletResponse resp) {
      setHeaders(req);
      setParameters(req);
    }
  }
  
  @BeforeClass
  public static void beforeTest() throws Exception {
    createJetty(ExternalPaths.EXAMPLE_HOME, null, null);
    jetty.getDispatchFilter().getServletHandler()
        .addServletWithMapping(RedirectServlet.class, "/redirect/*");
    jetty.getDispatchFilter().getServletHandler()
        .addServletWithMapping(SlowServlet.class, "/slow/*");
    jetty.getDispatchFilter().getServletHandler()
        .addServletWithMapping(DebugServlet.class, "/debug/*");
  }
  
  @Test
  public void testConnectionRefused() throws MalformedURLException {
    int unusedPort = findUnusedPort(); // XXX even if fwe found an unused port
                                       // it might not be unused anymore
    HttpSolrServer server = new HttpSolrServer("http://127.0.0.1:" + unusedPort
        + "/solr");
    SolrQuery q = new SolrQuery("*:*");
    try {
      QueryResponse response = server.query(q);
      fail("Should have thrown an exception.");
    } catch (SolrServerException e) {
      assertTrue(e.getMessage().contains("refused"));
    }
  }
  
  @Test
  public void testTimeout() throws Exception {
    HttpSolrServer server = new HttpSolrServer("http://127.0.0.1:"
        + jetty.getLocalPort() + "/solr/slow/foo");
    SolrQuery q = new SolrQuery("*:*");
    server.setSoTimeout(2000);
    try {
      QueryResponse response = server.query(q, METHOD.GET);
      fail("No exception thrown.");
    } catch (SolrServerException e) {
      assertTrue(e.getMessage().contains("Timeout"));
    }
  }
  
  @Test
  public void testQuery(){
    DebugServlet.clear();
    HttpSolrServer server = new HttpSolrServer("http://127.0.0.1:"
        + jetty.getLocalPort() + "/solr/debug/foo");
    SolrQuery q = new SolrQuery("foo");
    q.setParam("a", "\u1234");
    try {
      server.query(q, METHOD.GET);
    } catch (Throwable t) {}
    
    //default method
    assertEquals("get", DebugServlet.lastMethod);
    //agent
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    //default wt
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
    //default version
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    //agent
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    //keepalive
    assertEquals("keep-alive", DebugServlet.headers.get("Connection"));
    //content-type
    assertEquals(null, DebugServlet.headers.get("Content-Type"));
    //param encoding
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);

    //POST
    DebugServlet.clear();
    try {
      server.query(q, METHOD.POST);
    } catch (Throwable t) {}
    assertEquals("post", DebugServlet.lastMethod);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals("keep-alive", DebugServlet.headers.get("Connection"));
    assertEquals("application/x-www-form-urlencoded; charset=UTF-8", DebugServlet.headers.get("Content-Type"));
    assertEquals("UTF-8", DebugServlet.headers.get("Content-Charset"));

    //XML/GET
    server.setParser(new XMLResponseParser());
    DebugServlet.clear();
    try {
      server.query(q, METHOD.GET);
    } catch (Throwable t) {}
    assertEquals("get", DebugServlet.lastMethod);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals("keep-alive", DebugServlet.headers.get("Connection"));

    //XML/POST
    server.setParser(new XMLResponseParser());
    DebugServlet.clear();
    try {
      server.query(q, METHOD.POST);
    } catch (Throwable t) {}
    assertEquals("post", DebugServlet.lastMethod);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals("keep-alive", DebugServlet.headers.get("Connection"));
    assertEquals("application/x-www-form-urlencoded; charset=UTF-8", DebugServlet.headers.get("Content-Type"));
    assertEquals("UTF-8", DebugServlet.headers.get("Content-Charset"));
  }

  @Test
  public void testDelete(){
    DebugServlet.clear();
    HttpSolrServer server = new HttpSolrServer("http://127.0.0.1:"
        + jetty.getLocalPort() + "/solr/debug/foo");
    try {
      server.deleteById("id");
    } catch (Throwable t) {}
    
    //default method
    assertEquals("post", DebugServlet.lastMethod);
    //agent
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    //default wt
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
    //default version
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    //agent
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    //keepalive
    assertEquals("keep-alive", DebugServlet.headers.get("Connection"));

    //XML
    server.setParser(new XMLResponseParser());
    try {
      server.deleteByQuery("*:*");
    } catch (Throwable t) {}
    
    assertEquals("post", DebugServlet.lastMethod);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals("keep-alive", DebugServlet.headers.get("Connection"));
  }
  
  @Test
  public void testUpdate(){
    DebugServlet.clear();
    HttpSolrServer server = new HttpSolrServer("http://127.0.0.1:"
        + jetty.getLocalPort() + "/solr/debug/foo");
    UpdateRequest req = new UpdateRequest();
    req.add(new SolrInputDocument());
    req.setParam("a", "\u1234");
    try {
      server.request(req);
    } catch (Throwable t) {}
    
    //default method
    assertEquals("post", DebugServlet.lastMethod);
    //agent
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    //default wt
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
    //default version
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    //content type
    assertEquals("application/xml; charset=UTF-8", DebugServlet.headers.get("Content-Type"));
    //parameter encoding
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);

    //XML response
    server.setParser(new XMLResponseParser());
    try {
      server.request(req);
    } catch (Throwable t) {}
    assertEquals("post", DebugServlet.lastMethod);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("xml", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    assertEquals("application/xml; charset=UTF-8", DebugServlet.headers.get("Content-Type"));
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
    
    //javabin request
    server.setParser(new BinaryResponseParser());
    server.setRequestWriter(new BinaryRequestWriter());
    DebugServlet.clear();
    try {
      server.request(req);
    } catch (Throwable t) {}
    assertEquals("post", DebugServlet.lastMethod);
    assertEquals("Solr[" + org.apache.solr.client.solrj.impl.HttpSolrServer.class.getName() + "] 1.0", DebugServlet.headers.get("User-Agent"));
    assertEquals(1, DebugServlet.parameters.get(CommonParams.WT).length);
    assertEquals("javabin", DebugServlet.parameters.get(CommonParams.WT)[0]);
    assertEquals(1, DebugServlet.parameters.get(CommonParams.VERSION).length);
    assertEquals(server.getParser().getVersion(), DebugServlet.parameters.get(CommonParams.VERSION)[0]);
    assertEquals("application/javabin", DebugServlet.headers.get("Content-Type"));
    assertEquals(1, DebugServlet.parameters.get("a").length);
    assertEquals("\u1234", DebugServlet.parameters.get("a")[0]);
  }
  
  @Test
  public void testRedirect() throws Exception {
    HttpSolrServer server = new HttpSolrServer("http://127.0.0.1:"
        + jetty.getLocalPort() + "/solr/redirect/foo");
    SolrQuery q = new SolrQuery("*:*");
    // default = false
    try {
      QueryResponse response = server.query(q);
      fail("Should have thrown an exception.");
    } catch (SolrServerException e) {
      assertTrue(e.getMessage().contains("redirect"));
    }
    server.setFollowRedirects(true);
    try {
      QueryResponse response = server.query(q);
    } catch (Throwable t) {
      fail("Exception was thrown:" + t);
    }
  }
  
  @Test
  public void testCompression() throws Exception {
    HttpSolrServer server = new HttpSolrServer("http://127.0.0.1:"
        + jetty.getLocalPort() + "/solr/debug/foo");
    SolrQuery q = new SolrQuery("*:*");
    
    // verify request header gets set
    DebugServlet.clear();
    try {
      server.query(q);
    } catch (Throwable t) {}
    assertNull(DebugServlet.headers.get("Accept-Encoding"));
    server.setAllowCompression(true);
    try {
      server.query(q);
    } catch (Throwable t) {}
    assertNotNull(DebugServlet.headers.get("Accept-Encoding"));
    server.setAllowCompression(false);
    try {
      server.query(q);
    } catch (Throwable t) {}
    assertNull(DebugServlet.headers.get("Accept-Encoding"));
    
    // verify server compresses output
    HttpGet get = new HttpGet("http://127.0.0.1:" + jetty.getLocalPort()
        + "/solr/select?q=foo&wt=xml");
    get.setHeader("Accept-Encoding", "gzip");
    HttpClient client = HttpClientUtil.createClient(null);
    HttpEntity entity = null;
    try {
      HttpResponse response = client.execute(get);
      entity = response.getEntity();
      Header ceheader = entity.getContentEncoding();
      assertEquals("gzip", ceheader.getValue());
      
    } finally {
      if(entity!=null) {
        entity.getContent().close();
      }
      client.getConnectionManager().shutdown();
    }
    
    // verify compressed response can be handled
    server = new HttpSolrServer("http://127.0.0.1:" + jetty.getLocalPort()
        + "/solr");
    server.setAllowCompression(true);
    q = new SolrQuery("foo");
    QueryResponse response = server.query(q);
    assertEquals(0, response.getStatus());
  }
  
  @Test
  public void testSetParametersExternalClient(){
    HttpClient client = HttpClientUtil.createClient(null);
    HttpSolrServer server = new HttpSolrServer("http://127.0.0.1/", client);
    try {
      server.setMaxTotalConnections(1);
      fail("Operation should not succeed.");
    } catch (UnsupportedOperationException e) {}
    try {
      server.setDefaultMaxConnectionsPerHost(1);
      fail("Operation should not succeed.");
    } catch (UnsupportedOperationException e) {}
  }
  
  private int findUnusedPort() {
    for (int port = 0; port < 65535; port++) {
      Socket s = new Socket();
      try {
        s.bind(null);
        int availablePort = s.getLocalPort();
        s.close();
        return availablePort;
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    throw new RuntimeException("Could not find unused TCP port.");
  }
  
}
