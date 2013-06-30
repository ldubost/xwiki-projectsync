package org.xwiki.contrib.projectsync;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.auth.BasicScheme;
import org.xwiki.component.embed.EmbeddableComponentManager;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextException;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.diff.display.UnifiedDiffBlock;
import org.xwiki.diff.display.UnifiedDiffElement;
import org.xwiki.diff.internal.script.DiffDisplayerScriptService;
import org.xwiki.environment.Environment;
import org.xwiki.environment.internal.ServletEnvironment;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.rest.model.jaxb.Page;
import org.xwiki.script.service.ScriptService;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.plugin.diff.DiffPlugin;
import com.xpn.xwiki.util.XWikiStubContextProvider;
import com.xpn.xwiki.web.Utils;

import name.pachler.nio.file.*;

public class ProjectSync extends Thread {
    
    private String wikiUrl;
    private String wikiId = "xwiki";
    private String wikiType = "xe";
    private String username = "Admin";
    private String password = "admin";
    private String directory;
    private String[] syncSpaces = null;
    private String defaultUser = "xwiki:XWiki.Admin";
    private Date defaultDate = new Date("01/01/2013");
    private String defaultLanguage = "";
    private XWikiContext context;
    private DiffPlugin diff;
    private DiffDisplayerScriptService diffDisplayer;
    
    private static boolean xarFormat = false;
    private static boolean checkWiki = false;
    
    private WatchService watchService;
    
    public ProjectSync(String directory, Properties props) {
        this.directory = directory;
        this.wikiUrl = props.getProperty("wikiUrl", this.wikiUrl);
        this.wikiType = props.getProperty("wikiType", this.wikiType);
        this.wikiId = props.getProperty("wikiId", this.wikiId);
        this.syncSpaces = (props.getProperty("spaces")!=null) ? props.getProperty("spaces").split(",") : null;
        this.username = props.getProperty("username", this.username);
        this.password = props.getProperty("password", this.password);
    }
    
    public static void main(String[] argv) {
        String directory = "";
        
        for (String arg : argv) {
            if (arg.startsWith("-")) {
                if (arg.equals("-format")) {
                    xarFormat = true;
                }
            } else {
                if (directory.equals(""))
                    directory = arg;
            }
            
        }
        System.out.println("Checking direcotry " + directory);
        File dir = new File(directory);
        File projectsync = new File(dir, ".projectsync");
        Properties props = new Properties();
        try {
            props.load(new FileInputStream(projectsync));
        } catch (Exception e) {
            e.printStackTrace();
        } 
        System.out.println("Loaded properties " + props);
        
        ProjectSync sync = new ProjectSync(directory, props);
        sync.start();
    }

    @Override
    public void run() 
    {
     // Initialize Rendering components and allow getting instances
        EmbeddableComponentManager componentManager = new EmbeddableComponentManager();
        componentManager.initialize(this.getClass().getClassLoader());
        Utils.setComponentManager(componentManager);
        this.context = new XWikiContext();
        this.context.setWiki(new XWiki());
        this.diff = new DiffPlugin("diff", "diff", context);
        this.diffDisplayer = (DiffDisplayerScriptService) Utils.getComponent(ScriptService.class, "diff.display");
        
        ExecutionContextManager ecim = Utils.getComponent(ExecutionContextManager.class);
        ExecutionContext context = new ExecutionContext();
        try {
            ecim.initialize(context);
        } catch (ExecutionContextException e2) {
            // TODO Auto-generated catch block
            e2.printStackTrace();
        }
        
        ServletEnvironment env;
        try {
            env = (ServletEnvironment) componentManager.getInstance(Environment.class);
            env.setTemporaryDirectory(new File("."));
        } catch (ComponentLookupException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        try {
            defaultLanguage = getDefaultLanguage(); 
            if (defaultLanguage!="") {
                System.out.println("WARNING: wiki is not in multilingual mode, some pages cannot be synchronized. Language is set to " + defaultLanguage);
            } else {
                System.out.println("Wiki is set in multilingual mode.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        
        try {
        
        System.out.println("Run in thread");
        try {
            testRest();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        try {
            listDocuments();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            // testWatch();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        } finally {
            // 5.0 only
            // componentManager.dispose();
        }
        
    }
    
    
    public String getCleanedXML(XWikiDocument pagedoc, String defaultUser, Date defaultDate, XWikiContext context) throws XWikiException {
        XWikiDocument clonedDoc = pagedoc;

        // remove Tag object
        if (clonedDoc.getObject("XWiki.TagClass") != null) {
          clonedDoc.removeObject(clonedDoc.getObject("XWiki.TagClass"));
        }

        if (defaultUser!=null && !defaultUser.equals("")) {
          clonedDoc.setCreator(defaultUser);
          clonedDoc.setContentAuthor(defaultUser);
          clonedDoc.setAuthor(defaultUser);
        } else {
          clonedDoc.setCreator(clonedDoc.getAuthor());
          clonedDoc.setContentAuthor(clonedDoc.getAuthor());
        }
     
        if (defaultDate!=null) {
          clonedDoc.setCreationDate(defaultDate);
          clonedDoc.setContentUpdateDate(defaultDate);
          clonedDoc.setDate(defaultDate);
          clonedDoc.setVersion("1.1");
        } else {
          clonedDoc.setContentUpdateDate(clonedDoc.getDate());
          clonedDoc.setCreationDate(clonedDoc.getDate());
          clonedDoc.setVersion("1.1");
        }

        clonedDoc.setComment("");
        clonedDoc.setMinorEdit(false);
        String c = clonedDoc.toXML(true, false, false, false, context);
        return c.trim().replaceAll("[\r]","");
    }
    
    
   
    public void checkDocument(String space, File page) throws Exception {
        String spage = page.getName().substring(0, page.getName().length() - 4);
        int i = spage.lastIndexOf('.');
        String language = spage.substring(i+1);
        if (i!=-1 && language.length()==2) {
            spage = spage.substring(0, i);
        } else {
            language = "";
        }

        FileInputStream fis = new FileInputStream(page);
        XWikiDocument localDoc = new XWikiDocument(space, spage);
        localDoc.fromXML(fis);
        
        if (checkWiki) {
            String cleanedLocalContent = getCleanedXML(localDoc, defaultUser, defaultDate, context);
                   String url = getXMLURL(space, spage, language);
        System.out.println("URL: " + url);
        String remoteContent = getXMLContent(url);
        XWikiDocument remoteDoc = new XWikiDocument(space, spage);
        remoteDoc.fromXML(remoteContent);
        String cleanedRemoteContent = getCleanedXML(remoteDoc, defaultUser, defaultDate, context);
        
        if (!remoteDoc.getLanguage().equals(localDoc.getLanguage())) {
           if (defaultLanguage.equals("")) {
               System.out.println("ERROR: page " + page.getName() + " local and remote don't have the same language. This could be because of the wiki default language setting.");               
           } else {
               System.out.println("WARNING: page " + page.getName() + " cannot be synced because the wiki is not in multilingual mode.");
           }
        } else {        
        if (!cleanedRemoteContent.equals(cleanedLocalContent)) {
           System.out.println("Page " + space + "." + spage + " (" + language + ") is different"); 
           List<UnifiedDiffBlock<String, Character>> result = diffDisplayer.unified(cleanedRemoteContent, cleanedLocalContent);
           for (UnifiedDiffBlock<String, Character> block : result) {
               for (UnifiedDiffElement<String, Character> line : block) {
                   if (line.isAdded()) 
                    System.out.println("+ " + line.getValue());
                   if (line.isDeleted()) 
                       System.out.println("- " + line.getValue());
               }
           }
           }
        }
        }
        
        // if xarFormat is asked for we should look into splitting the document
        // and normalize the XML
        if (xarFormat) {
            String content = localDoc.getContent();
            String extension = ".xwiki";
            
            if (localDoc.getObject("XWiki.TranslationDocumentClass")!=null)
                extension = ".properties";
            
            String contentPath = page.getName().substring(0, page.getName().length() - 4) + extension;
            File contentFile = new File(page.getParentFile(), contentPath);
            
            saveIfDifferent(content, contentFile);
            
            for (DocumentReference className : localDoc.getXObjects().keySet()) {
                // System.out.println("Looking at className " + className);
                for (BaseObject object : localDoc.getXObjects(className)) {
                    if (object==null)
                        continue;
                    // System.out.println("Looking at object: " + object.getClassName() + " " + object.getNumber());
                    for (Object prop1 : object.getProperties()) {
                        BaseProperty prop = (BaseProperty) prop1;
                        // System.out.println("Looking at prop: " + prop.getName() + " " + prop.getClassType());
                        if (prop.getClassType().contains("LargeStringProperty")) {
                            System.out.println("We found a textarea field: " + prop.getName() + " in " + object.getClassName() + " " + object.getNumber());
                            extension = ".xwiki";
                            if (object.getClassName().equals("XWiki.JavascriptExtension"))
                                extension = ".js";
                            else if (object.getClassName().equals("XWiki.StyleSheetExtension"))
                                extension = ".css";
                            else if (object.getClassName().equals("XWiki.UIExtensionClass"))
                                extension = ".properties";
                            else if (object.getClassName().equals("XWiki.XWikiSkins"))
                                extension = "";
                            
                            String fieldPath = page.getName().substring(0, page.getName().length() - 4) + "_" + object.getClassName() + "_" + object.getNumber() + "_" + prop.getName() + extension;
                            File fieldFile = new File(page.getParentFile(), fieldPath);
                            // System.out.println("Ready to save into " + fieldPath);
                            saveIfDifferent((String) prop.getValue(), fieldFile);                        
                        }
                    }
                }
            }
            
            for (XWikiAttachment attach: localDoc.getAttachmentList()) {
                byte[] data = attach.getAttachment_content().getContent();
                String dataPath = page.getName().substring(0, page.getName().length() - 4) + "_" + attach.getFilename();
                File dataFile = new File(page.getParentFile(), dataPath);              
                saveIfDifferentAsBytes(data, dataFile);
            }           
        }
    }

    private void saveIfDifferent(String content, File contentFile) throws IOException, FileNotFoundException
    {
        String currentContent = (contentFile.exists()) ? IOUtils.toString(new FileInputStream(contentFile)) : "";
        if (!currentContent.equals(content)) {
            System.out.println("Ready to save into " + contentFile.getName());
            IOUtils.write(content, new FileOutputStream(contentFile));
        }
    }
    
    private void saveIfDifferentAsBytes(byte[] content, File contentFile) throws IOException, FileNotFoundException
    {
        byte[] currentContent = ((contentFile.exists()) ? IOUtils.toByteArray(new FileInputStream(contentFile)) : new byte[0]);
        if (!currentContent.equals(content)) {
            System.out.println("Ready to save into " + contentFile.getName());
            IOUtils.write(content, new FileOutputStream(contentFile));
        }
    }
    
    public void listDocuments() throws Exception {
        File dir = new File(directory);
        File spaces = new File(dir, "src/main/resources");
        for (File space : spaces.listFiles()) {
            System.out.println("Looking at space: " + space.getName());
            for (File page : space.listFiles()) {
                System.out.println("Looking at page: " + page.getName());
                if (page.getName().endsWith(".xml")) {
                    checkDocument(space.getName(), page);
                }
            }
        }   
    }
    
    
    
    public void testWatch() {
        watchService = FileSystems.getDefault().newWatchService();
        Path watchedPath = Paths.get(directory);
        WatchKey key = null;
        try {
            key = watchedPath.register(watchService, StandardWatchEventKind.ENTRY_CREATE, StandardWatchEventKind.ENTRY_DELETE, StandardWatchEventKind.ENTRY_MODIFY);
        } catch (UnsupportedOperationException uox){
            System.err.println("file watching not supported!");
            return;
        }catch (IOException iox){
            System.err.println("I/O errors");
            return;
        }

        System.out.println("Watching directory: " + directory);

        for(;;){
            // take() will block until a file has been created/deleted
            WatchKey signalledKey;
            try {
                signalledKey = watchService.take();
            } catch (InterruptedException ix){
                // we'll ignore being interrupted
                continue;
            } catch (ClosedWatchServiceException cwse){
                // other thread closed watch service
                System.out.println("watch service closed, terminating.");
                break;
            }

            // get list of events from key
            List<WatchEvent<?>> list = signalledKey.pollEvents();

            // VERY IMPORTANT! call reset() AFTER pollEvents() to allow the
            // key to be reported again by the watch service
            signalledKey.reset();

            // we'll simply print what has happened; real applications
            // will do something more sensible here
            for(WatchEvent e : list){
                String message = "";
                if(e.kind() == StandardWatchEventKind.ENTRY_CREATE){
                    Path context = (Path)e.context();
                    message = context.toString() + " created";
                } else if(e.kind() == StandardWatchEventKind.ENTRY_DELETE){
                    Path context = (Path)e.context();
                    message = context.toString() + " deleted";
                } else if(e.kind() == StandardWatchEventKind.ENTRY_MODIFY){
                    Path context = (Path)e.context();
                    message = context.toString() + " modified";
                } else if(e.kind() == StandardWatchEventKind.OVERFLOW){
                    message = "OVERFLOW: more changes happened than we could retreive";
                }
                System.out.println(message);
            }
        }
    }
    
    public String getRestURL(String restPath) {
        return wikiUrl + "rest/wikis/" + wikiId + "/" + restPath;
    }
    
    public String getXMLURL(String space, String page, String language) {
        return wikiUrl + "wiki/" + wikiId + "/view/" + space + "/" + page + "?xpage=xml&language=" + language;
    }
    
    public String getXMLContent(String url) throws Exception {
        
        HttpClient httpClient = new DefaultHttpClient();
        UsernamePasswordCredentials creds = new UsernamePasswordCredentials(username, password);
        HttpGet getMethod = new HttpGet(url);
        getMethod.addHeader(new BasicScheme().authenticate(creds, getMethod));        
        HttpResponse response = httpClient.execute(getMethod);
        
        InputStream is = response.getEntity().getContent();
        return IOUtils.toString(is);
    }
    
    public String getWikiPreferences() throws Exception {
        String url = getXMLURL("XWiki", "XWikiPreferences", "");
        return getXMLContent(url);
    }
    
    public String getDefaultLanguage() throws XWikiException, Exception {
        XWikiDocument doc = new XWikiDocument("XWiki", "XWikiPreferences");
        doc.fromXML(getWikiPreferences());
        BaseObject prefsObj = doc.getObject("XWiki.XWikiPreferences");
        int multilingual = prefsObj.getIntValue("multilingual");
        String defaultLanguage = prefsObj.getStringValue("default_language");
        if (multilingual==1)
            return "";
        else 
            return defaultLanguage;
            
    }
    
    public void testRest() throws JAXBException, AuthenticationException, ClientProtocolException, IOException {
        HttpClient httpClient = new DefaultHttpClient();
        UsernamePasswordCredentials creds = new UsernamePasswordCredentials(username, password);

        JAXBContext context = JAXBContext.newInstance("org.xwiki.rest.model.jaxb");
        Unmarshaller unmarshaller = context.createUnmarshaller();

        String url = getRestURL("spaces/Main/pages/WebHome");
        System.out.println("Checking url: " + url);
        HttpGet getMethod = new HttpGet(url);
        getMethod.addHeader("Accept", "application/xml");
        getMethod.addHeader(new BasicScheme().authenticate(creds, getMethod));        
        
        HttpResponse response = httpClient.execute(getMethod);
        
        Page page = (Page) unmarshaller.unmarshal(response.getEntity().getContent());
        System.out.println(page.getContent());
    }
    
}