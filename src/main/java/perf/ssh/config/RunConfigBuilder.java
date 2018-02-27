package perf.ssh.config;

import org.slf4j.ext.XLogger;
import org.slf4j.ext.XLoggerFactory;
import perf.ssh.Host;
import perf.ssh.RunValidation;
import perf.ssh.State;
import perf.ssh.cmd.Cmd;
import perf.ssh.cmd.CommandSummary;
import perf.ssh.cmd.Script;
import perf.ssh.cmd.impl.ScriptCmd;
import perf.yaup.HashedLists;
import perf.yaup.HashedSets;
import perf.yaup.json.Json;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static perf.ssh.cmd.Cmd.STATE_PREFIX;
import static perf.ssh.config.YamlParser.*;

public class RunConfigBuilder {

    final static XLogger logger = XLoggerFactory.getXLogger(MethodHandles.lookup().lookupClass());

    private static final Json EMPTY_ARRAY = new Json();

    private static final String NAME = "name";
    private static final String SCRIPTS = "scripts";
    private static final String ROLES = "roles";
    private static final String HOSTS = "hosts";
    private static final String STATES = "states";
    private static final String SETUP_SCRIPTS = "setup-scripts";
    private static final String RUN_SCRIPTS = "run-scripts";
    private static final String CLEANUP_SCRIPTS = "cleanup-scripts";

    private static final String ALL_ROLE = "all";
    private static final String RUN_STATE = "run";
    private static final String SCRIPT_DIR = "ENV.SCRIPT_DIR";

    public static final String DEFAULT_KNOWN_HOSTS = System.getProperty("user.home")+"/.ssh/known_hosts";
    public static final String DEFAULT_IDENTITY = System.getProperty("user.home")+"/.ssh/id_rsa";
    public static final String DEFAULT_PASSPHRASE = null;

    public static final String HOST_EXPRESSION_PREFIX = "=";
    public static final String HOST_EXPRESSING_INCLUDE = "+";
    public static final String HOST_EXPRESSION_EXCLUDE = "-";


    private String identity = DEFAULT_IDENTITY;
    private String knownHosts = DEFAULT_KNOWN_HOSTS;
    private String passphrase = DEFAULT_PASSPHRASE;

    private String name = null;
    private State state;

    private HashMap<String,Script> scripts;

    private HashedSets<String,String> roleHosts;
    private HashMap<String,String> roleHostExpression;

    private HashedLists<String,ScriptCmd> roleSetup;
    private HashedLists<String,ScriptCmd> roleRun;
    private HashedLists<String,ScriptCmd> roleCleanup;

    private HashMap<String,String> hostAlias;

    private CmdBuilder cmdBuilder;
    private List<String> errors;

    private boolean isValid = false;

    public Set<String> getRolesWithScripts(){
        HashSet<String> rtrn = new HashSet<>();
        rtrn.addAll(roleSetup.keys());
        rtrn.addAll(roleRun.keys());
        rtrn.addAll(roleCleanup.keys());
        return rtrn;
    }
    public Set<String> getRolesWithoutHosts(){
        Set<String> rtrn = getRolesWithScripts();
        rtrn.removeAll(roleHostExpression.keySet());
        rtrn.removeAll(roleHosts.keys());
        return rtrn;
    }
    public Set<String> getRolesWithoutScripts(){
        Set<String> rtrn = new HashSet<>();
        rtrn.addAll(roleHosts.keys());
        rtrn.addAll(roleHostExpression.keySet());
        rtrn.removeAll(getRolesWithScripts());
        return rtrn;
    }

    public RunConfigBuilder(CmdBuilder cmdBuilder){
        this("run-"+System.currentTimeMillis(),cmdBuilder);
    }
    public RunConfigBuilder(String name,CmdBuilder cmdBuilder){
        this.name = name;
        this.cmdBuilder = cmdBuilder;
        scripts = new HashMap<>();
        state = new State(null,State.RUN_PREFIX);
        roleHosts = new HashedSets<>();
        roleHostExpression = new HashMap<>();
        hostAlias = new HashMap<>();

        roleSetup = new HashedLists<>();
        roleRun = new HashedLists<>();
        roleCleanup = new HashedLists<>();
        errors = new LinkedList<>();
    }



    public void eachChildArray(Json target, BiConsumer<Integer,Json> consumer){

        Json childArray = target.getJson(CHILD, EMPTY_ARRAY);
        for (int childIndex = 0; childIndex < childArray.size(); childIndex++) {
            Json childEntry = childArray.getJson(childIndex);
            consumer.accept(childIndex,childEntry);
        }
    }
    public void eachChildEntry(Json target, BiConsumer<Integer,Json> consumer){

        Json childArray = target.getJson(CHILD,EMPTY_ARRAY);
        for(int childIndex=0; childIndex<childArray.size(); childIndex++){
            Json childEntry = childArray.getJson(childIndex,EMPTY_ARRAY);
            for(int entryIndx=0; entryIndx < childEntry.size(); entryIndx++){
                Json entry = childEntry.getJson(entryIndx,EMPTY_ARRAY);
                consumer.accept(childIndex,entry);
            }
        }
    }
    public Map<String,String> yamlChildMap(Json json){
        Map<String,String> rtrn = new LinkedHashMap<>();
        eachChildEntry(json,(i,childEntry)->{
            if(childEntry.has(KEY) && childEntry.has(VALUE)) {
                rtrn.put(childEntry.getString(KEY), childEntry.getString(VALUE));
            }

        });
        return rtrn;
    }


    public void addError(String error){
        errors.add(error);
    }
    public void addErrors(Collection<String> error){
        errors.addAll(error);
    }
    public int errorCount(){return errors.size();}

    public boolean loadYaml(YamlParser yamlParser) {
        boolean ok = true;
        if(yamlParser.hasErrors()){
            addErrors(yamlParser.getErrors());
            ok = false;
        }else {
            for(String yamlPath : yamlParser.fileNames()){
                Json yamlJson = yamlParser.getJson(yamlPath);
                boolean docOk = loadYamlJson(yamlJson,yamlPath);
                ok = ok && docOk;
            }
        }
        return ok;
    }
    public boolean loadYamlJson(Json yamlJson,String yamlPath){
        boolean ok = true;
        if (yamlJson.isArray()) {
            for (int i = 0; i < yamlJson.size(); i++) {
                Object yamlObj = yamlJson.get(i);
                if (yamlObj instanceof Json) {
                    Json yamlEntry = (Json) yamlObj;
                    String entryKey = yamlEntry.getString(KEY);
                    String entryValue = yamlEntry.getString(VALUE);
                    Json entryChildJson = yamlEntry.getJson(CHILD, EMPTY_ARRAY);
                    if (entryKey == null) {

                    }
                    switch (entryKey) {
                        case NAME:
                            if (entryValue != null && !entryValue.isEmpty()) {
                                setName(entryValue);
                            }
                            break;
                        case SCRIPTS:
                            eachChildEntry(yamlEntry, (entryIndex, scriptEntry) -> {
                                String scriptName = scriptEntry.getString(KEY, "");

                                //Only accept the first definition so make sure the most important yaml is first :)
                                if(hasScript(scriptName)){
                                    logger.warn("{} tried to add script {} which already exists",yamlPath,scriptName);
                                }else {
                                    Script newScript = new Script(scriptName);
                                    File yamlFile = new File(yamlPath);
                                    String scriptDir = yamlFile.exists() ? yamlFile.getParent() : yamlPath;
                                    newScript.with(SCRIPT_DIR,scriptDir);
                                    eachChildArray(scriptEntry, (commandIndex, scriptCommand) -> {
                                        Cmd childCmd = cmdBuilder.buildYamlCommand(scriptCommand, newScript);
                                        newScript.then(childCmd);
                                    });
                                    addScript(newScript);
                                }
                            });
                            break;
                        case ROLES:
                            eachChildEntry(yamlEntry, (entryIndex, roleEntry) -> {
                                String roleName = roleEntry.getString(KEY, "");

                                //roles merge so no warning if already defined

                                eachChildEntry(roleEntry, (sectionIndex, roleSection) -> {
                                    String sectionName = roleSection.getString(KEY, "");
                                    if (HOSTS.equals(sectionName)) {
                                        if(roleSection.has(VALUE)){
                                            String roleSectionValue = roleSection.getString(VALUE).trim();
                                            if(roleSectionValue.startsWith(HOST_EXPRESSION_PREFIX)) {
                                                setRoleHostExpession(roleName, roleSection.getString(VALUE, ""));
                                            }else{
                                                //assume the value is just the only host? This could also just be a syntax error
                                                //just going with syntax error to simplify the parsing
                                                addError(roleName+" hosts should either be a host expression (= role [+-] otherRole) or a list of host aliases but was "+roleSectionValue);
                                            }
                                        }
                                        eachChildEntry(roleSection, (hostIndex, host) -> {
                                            String hostReference = host.getString(KEY, "");
                                            if (hostReference.isEmpty()) {
                                                //TODO log error about parsing the host
                                            } else {
                                                addHostToRole(roleName, host.getString(KEY, ""));
                                            }
                                        });

                                    } else {
                                        eachChildEntry(roleSection, (scriptIndex, scriptRefernce) -> {
                                            String scriptName = scriptRefernce.getString(KEY);

                                            Map<String, String> scriptWiths = new LinkedHashMap<>();
                                            eachChildEntry(scriptRefernce, (childIndex, scriptChild) -> {
                                                String childName = scriptChild.getString(KEY);
                                                if (WITH.equalsIgnoreCase(childName)) {
                                                    Map<String, String> withs = yamlChildMap(scriptChild);
                                                    scriptWiths.putAll(withs);
                                                }
                                            });

                                            switch (sectionName) {
                                                case SETUP_SCRIPTS:
                                                    addRoleSetup(roleName, scriptName, scriptWiths);
                                                    break;
                                                case RUN_SCRIPTS:
                                                    addRoleRun(roleName, scriptName, scriptWiths);
                                                    break;
                                                case CLEANUP_SCRIPTS:
                                                    addRoleCleanup(roleName, scriptName, scriptWiths);
                                                    break;
                                            }
                                        });
                                    }
                                });
                            });

                            break;
                        case HOSTS:
                            eachChildEntry(yamlEntry, (hostIndex, host) -> {
                                String hostName = host.getString(KEY);
                                String hostValue = host.getString(VALUE, "");
                                if (hostValue.isEmpty()) {

                                    Map<String, String> hostMap = yamlChildMap(host);

                                    if (hostMap.containsKey("username") && hostMap.containsKey("hostname")) {
                                        String un = hostMap.get("username").toString();
                                        String hn = hostMap.get("hostname").toString();
                                        int port = hostMap.containsKey("port") ? Integer.parseInt(hostMap.get("port")) : Host.DEFAULT_PORT;
                                        hostValue = un+ "@" + hn + ":" + port;
                                    }else{

                                    }
                                }
                                if (!hostValue.isEmpty()) {

                                    addHostAlias(hostName, hostValue);
                                }

                            });
                            break;
                        case STATES:
                            eachChildEntry(yamlEntry, (stateIndex, stateJson) -> {
                                String stateName = stateJson.getString(KEY, "");
                                if(stateJson.has(VALUE)){
                                    String stateValue = stateJson.getString(VALUE);
                                    setRunState(stateName,stateValue);
                                }else {
                                    eachChildEntry(stateJson, (entryIndex, entry) -> {
                                        if (RUN_STATE.equals(stateName)) {
                                            if (!entry.has(VALUE) && entry.has(CHILD)) {
                                                String hostName = entry.getString(KEY);
                                                eachChildEntry(entry, (hostEntryIndex, hostEntry) -> {
                                                    if (!hostEntry.has(VALUE) && hostEntry.has(CHILD)) {
                                                        String scriptName = hostEntry.getString(KEY);
                                                        eachChildEntry(hostEntry, (scriptEntryIndex, scriptEntry) -> {
                                                            //TODO add script entry under host
                                                        });
                                                    } else {
                                                        //TODO add host entry
                                                    }
                                                });
                                            } else {
                                                setRunState(entry.getString(KEY), entry.getString(VALUE));
                                            }
                                        } else {
                                            setHostState(stateName, entry.getString(KEY), entry.getString(VALUE));
                                        }
                                    });
                                }
                            });
                            break;
                        default:
                            //umm...what is this?
                    }
                } else {
                    ok = false;
                    break;
                }
            }
        } else {
            ok = false;
        }

        return ok;
    }


    public void setName(String name){
        if(this.name == null) {
            this.name = name;
        }
    }
    public String getName(){return name;}

    public String getKnownHosts(){return knownHosts;}
    public void setKnownHosts(String knownHosts){
        this.knownHosts = knownHosts;
    }
    public String getIdentity(){return identity;}
    public void setIdentity(String identity){
        this.identity = identity;
    }
    public String getPassphrase(){return passphrase;}
    public void setPassphrase(String passphrase){
        this.passphrase = passphrase;
    }

    public void setRoleHostExpession(String roleName,String expression){
        roleHostExpression.put(roleName,expression);
    }
    public void addHostToRole(String name,String hostReference){
        roleHosts.put(name,hostReference);
    }
    public void addHostAlias(String alias,String host){
        hostAlias.put(alias,host);
        hostAlias.put(host,host);
    }

    public void addRoleSetup(String role, String script, Map<String,String> with){
        addRoleScript(role,script,with,roleSetup);
    }
    public void addRoleRun(String role, String script, Map<String,String> with){
        addRoleScript(role,script,with,roleRun);
    }
    public void addRoleCleanup(String role, String script, Map<String,String> with){
        addRoleScript(role,script,with,roleCleanup);
    }
    private void addRoleScript(String role,String script,Map<String,String> with,HashedLists<String,ScriptCmd> target){
        ScriptCmd cmd = Cmd.script(script);
        if(with!=null && !with.isEmpty()){
            cmd.with(with);
        }
        target.put(role,cmd);
    }

    public void forceRunState(String key,String value){
        state.set(key,value);
    }
    public void setRunState(String key,String value){
        if(!state.has(key)){
            state.set(key, value);
        } else {
            //TODO log the error

        }
    }
    public void setHostState(String host,String key,String value){
        State target = state.getChild(host,State.HOST_PREFIX);
        if(!target.has(key)){
            target.set(key,value);
        }else{

        }
        state.getChild(host,State.HOST_PREFIX).set(key,value);
    }
    public boolean addScript(Script script){
        if(scripts.containsKey(script.getName())){
            return false;
        }else{
            scripts.put(script.getName(),script);
        }
        return true;
    }
    public boolean hasScript(String name){
        return scripts.containsKey(name);
    }
    public Script getScript(String name){
        return getScript(name,null);
    }
    public Script getScript(String name, Cmd command){
        if(name.contains(STATE_PREFIX)){
            String scriptNameWithVariable = name;
            name = Cmd.populateStateVariables(name,command,state);

        }
        Script script = scripts.get(name);
        if(script==null){ // we don't find it
        }
        return script;
    }


    public boolean isValid(){
        isValid = validate();
        return isValid;
    }

    private boolean validate(){
        boolean rtrn = true;
        if(errorCount()>0){
            return false;
        }else{
            RunValidation runValidation = runValidation();
            if(!runValidation.isValid()){
                return false;
            }
        }
        return true;
    }
    public RunValidation runValidation(){
        return new RunValidation(validate(roleSetup,roleHosts),validate(roleRun,roleHosts),validate(roleCleanup,roleHosts));
    }

    private StageValidation validate(HashedLists<String,ScriptCmd> stage, HashedSets<String,String> hosts){
        final StageValidation rtrn = new StageValidation();

        stage.keys().forEach(roleName->{

            stage.get(roleName).forEach(scriptCmd -> {
                String scriptName = scriptCmd.getName();
                Script script = getScript(scriptName,scriptCmd);
                if(script == null){
                    rtrn.addError(roleName+" missing script "+scriptCmd.getName());
                }else{
                    hosts.get(roleName).forEach(host->{
                        CommandSummary summary = new CommandSummary(script,this);
                        summary.getWaits().forEach(rtrn::addWait);
                        summary.getSignals().forEach(rtrn::addSignal);
                        summary.getWarnings().forEach(rtrn::addError);
                    });
                }
            });
        });
        List<String> noSignal = rtrn.getWaiters().stream().filter((waitName)->!rtrn.getSignals().contains(waitName)).collect(Collectors.toList());
        List<String> noWaiters = rtrn.getSignals().stream().filter((signalName)->!rtrn.getWaiters().contains(signalName)).collect(Collectors.toList());
        if(!noSignal.isEmpty()){
            rtrn.addError("missing signals for "+noSignal);
        }
        return rtrn;

    }

    public RunConfig buildConfig(){

        Map<String,Host> seenHosts = new HashMap<>();

        Map<Host,Cmd> setupCmds = new HashMap<>();
        HashedLists<Host,ScriptCmd> runScripts = new HashedLists<>();
        Map<Host,Cmd> cleanupCmds = new HashMap<>();

        roleHosts.forEach((roleName,hostSet)->{
            for(String hostShortname : hostSet){
                String fullyQualified = hostAlias.get(hostShortname);
                if(fullyQualified!=null && !fullyQualified.isEmpty()){
                    if(seenHosts.containsKey(fullyQualified)){

                    }else{
                        if(fullyQualified.contains("@")){
                            String username = fullyQualified.substring(0,fullyQualified.indexOf("@"));
                            String hostname = fullyQualified.substring(fullyQualified.indexOf("@")+1);
                            int port = Host.DEFAULT_PORT;
                            if(hostname.contains(":")){
                                port = Integer.parseInt(hostname.substring(hostname.indexOf(":")+1));
                                hostname = hostname.substring(0,hostname.indexOf(":"));
                            }
                            Host newHost = new Host(username,hostname,port);
                            seenHosts.put(fullyQualified,newHost);//might omit port
                            seenHosts.put(hostShortname,newHost);
                            seenHosts.put(newHost.toString(),newHost);//could duplicate fullyQualified but guarantees to include port
                        }else{
                            addError("Host "+hostShortname+" = "+fullyQualified+" needs to match user@hostName but is missing an @");
                        }
                    }
                }else{
                    addError("Host "+hostShortname+" was added without a fully qualified host representation matching user@hostName:port");
                    //WTF, how are we missing a host reference?
                }
            }
        });

        //ALL_ROLE automatically includes all the hosts in use for any role
        Set<Host> uniqueHosts = new HashSet<>(seenHosts.values());
        roleHosts.putAll(ALL_ROLE,uniqueHosts.stream().map(Host::toString).collect(Collectors.toList()));

        //roleHostExpessions
        roleHostExpression.forEach((roleName,expession)->{
            List<String> split = CmdBuilder.split(expession);
            Set<Host> toAdd = new HashSet<>();
            Set<Host> toRemove = new HashSet<>();

            for(int i=0; i<split.size(); i++){
                String token = split.get(i);
                if( token.equals(HOST_EXPRESSION_PREFIX) || token.equals(HOST_EXPRESSING_INCLUDE) ){
                    if( i + 1 < split.size() ) {
                        toAdd.addAll(roleHosts.get(split.get(i+1)).stream().map(seenHosts::get).collect(Collectors.toList()));
                        i++;
                    }else{
                        //how does the expression end with an = or +?
                        addError("host expresion for "+roleName+" should not end with "+token);
                    }
                } else if (token.equals(HOST_EXPRESSION_EXCLUDE)){
                    if( i + 1 < split.size() ) {
                        toRemove.addAll(roleHosts.get(split.get(i+1)).stream().map(seenHosts::get).collect(Collectors.toList()));
                        i++;
                    }else{
                        //how does an expression end with -
                        addError("host expresion for "+roleName+" should not end with "+token);
                    }
                }else{
                    addError("host expressions should be = <role> [+-] <role>... but "+roleName+" could not parse "+token+" in: "+expession);
                }
            }

            toAdd.removeAll(toRemove);

            if(!toAdd.isEmpty()){
                roleHosts.putAll(roleName,toAdd.stream().map(Host::toString).collect(Collectors.toList()));
            }else{

            }
        });

        //setup commands
        for(String roleName : roleSetup.keys()){
            List<ScriptCmd> cmds = roleSetup.get(roleName);
            if (!cmds.isEmpty()) {
                for (String hostShortname : roleHosts.get(roleName)) {
                    Host h = seenHosts.get(hostShortname);
                    if (h == null) {
                        addError(roleName + " is missing a host definition for " + hostShortname+"\n has "+seenHosts.keySet());
                    } else {
                        if (!setupCmds.containsKey(h)) {
                            Cmd hostSetupCmd = new Script("setup:" + h.toString());

                            setupCmds.put(h, hostSetupCmd);
                        }
                        //get the cmd from setupCmds because multiple roles can share a host
                        cmds.forEach(scriptCmd -> setupCmds.get(h).then(scriptCmd.deepCopy()));
                    }
                }
            }

        }
        //cleanup commands
        for(String roleName : roleCleanup.keys()){
            List<ScriptCmd> cmds = roleCleanup.get(roleName);
            for (String hostShortname : roleHosts.get(roleName)) {
                Host h = seenHosts.get(hostShortname);
                if (h == null) {
                    addError(roleName + " is missing a host definition for " + hostShortname+"\n has "+seenHosts.keySet());
                } else {
                    if (!cleanupCmds.containsKey(h)) {
                        Cmd hostSetupCmd = new Script("cleanup:" + h.toString());
                        cleanupCmds.put(h, hostSetupCmd);
                    }
                    //get the cmd from setupCmds because multiple roles can share a host
                    cmds.forEach(scriptCmd -> cleanupCmds.get(h).then(scriptCmd.deepCopy()));
                }
            }
        }
        //run commands
        for(String roleName : roleRun.keys()){
            List<ScriptCmd> cmds = roleRun.get(roleName);
            for (String hostShortname : roleHosts.get(roleName)) {
                Host h = seenHosts.get(hostShortname);
                if (h == null) {
                    addError(roleName + " is missing a host definition for " + hostShortname+"\n has "+seenHosts.keySet());
                } else {
                    cmds.forEach(scriptCmd -> {
                        runScripts.put(h, scriptCmd);
                    });
                }
            }
        }
        if(errorCount() > 0){
            return new RunConfig(getName(),errors);
        }else {
            return new RunConfig(
                    getName(),
                    scripts,
                    state,
                    setupCmds,
                    runScripts,
                    cleanupCmds,
                    runValidation(),
                    getKnownHosts(),
                    getIdentity(),
                    getPassphrase()
            );
        }
    }

}