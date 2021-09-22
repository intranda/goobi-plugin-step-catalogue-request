package de.intranda.goobi.plugins;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.cli.helper.StringPair;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.VariableReplacer;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Corporate;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.IncompletePersonObjectException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;

@PluginImplementation
@Log4j2
@EqualsAndHashCode(callSuper = false)
public @Data class CatalogueRequestPlugin implements IStepPluginVersion2 {

    private PluginGuiType pluginGuiType = PluginGuiType.NONE;
    private PluginType type = PluginType.Step;

    private String title = "intranda_step_catalogue_request";

    private String pagePath = "";
    protected Step step;
    protected String returnPath;
    protected Process process;
    protected Prefs prefs;

    protected String configCatalogue = "";
    //    protected String configCatalogueField1 = "";
    //    protected String configCatalogueId1 = "";
    private boolean configIgnoreMissingData = false;
    private boolean configIgnoreRequestIssues = false;
    private boolean configMergeRecords = false;
    private boolean configAnalyseSubElements = false;
    private List<String> configSkipFields = null;

    private List<StringPair> configuredFields;

    /**
     * run this plugin and execute the catalogue update
     */
    @Override
    public PluginReturnValue run() {
        log.debug("Starting catalogue request using catalogue: {} for process {}", configCatalogue, process.getTitel());

        // first read the original METS file for the process
        Fileformat ffOld = null;
        DigitalDocument dd = null;
        DocStruct topstructOld = null;

        DocStruct anchorOld = null;
        DocStruct physOld = null;
        try {
            ffOld = process.readMetadataFile();
            if (ffOld == null) {
                log.error("Metadata file is not readable for process with ID " + step.getProcessId());
                Helper.setFehlerMeldung("Metadata file is not readable for process with ID " + step.getProcessId());
                return PluginReturnValue.ERROR;
            }
            dd = ffOld.getDigitalDocument();
            topstructOld = dd.getLogicalDocStruct();
            if (topstructOld.getType().isAnchor()) {
                anchorOld = topstructOld;
                topstructOld = topstructOld.getAllChildren().get(0);
            }
            physOld = dd.getPhysicalDocStruct();

        } catch (Exception e) {
            log.error("An exception occurred while reading the metadata file for process with ID " + step.getProcessId(), e);
            Helper.setFehlerMeldung("An exception occurred while reading the metadata file for process with ID " + step.getProcessId(), e);
            return PluginReturnValue.ERROR;
        }

        // create a VariableReplacer to transform the identifier field from the configuration into a real value
        VariableReplacer replacer = new VariableReplacer(dd, prefs, step.getProzess(), step);
        List<StringPair> valueList = new ArrayList<>(configuredFields.size());
        for (StringPair entry : configuredFields) {
            String value = replacer.replace(entry.getTwo());
            if (StringUtils.isNotBlank(value)) {
                valueList.add(new StringPair(entry.getOne(), value));
                log.debug("Using field {} and value {} for the catalogue request", entry.getOne(), value);
            }
        }

        if (valueList.isEmpty()) {
            if (configIgnoreMissingData) {
                log.debug("No catalogue identifier found. No automatic catalogue request possible. Move on with workflow.");
                Helper.setMeldung("No catalogue identifier found. No automatic catalogue request possible.");
                Helper.addMessageToProcessLog(step.getProzess().getId(), LogType.INFO,
                        "No catalogue identifier found. No automatic catalogue request possible. Move on with workflow.");
                return PluginReturnValue.FINISH;
            } else {
                log.error("No catalogue identifier found. No automatic catalogue request possible.");
                Helper.setFehlerMeldung("No catalogue identifier found. No automatic catalogue request possible.");
                Helper.addMessageToProcessLog(step.getProzess().getId(), LogType.ERROR,
                        "No catalogue identifier found. No automatic catalogue request possible.");
                return PluginReturnValue.ERROR;
            }
        }

        String processTemplateName = "";
        List<Processproperty> properties = process.getEigenschaften();
        if (properties != null) {
            for (Processproperty pp : properties) {
                if ("Template".equals(pp.getTitel())) {
                    processTemplateName = pp.getWert();
                }
            }
        }

        // request the wished catalogue with the correct identifier
        Fileformat ffNew = null;
        IOpacPlugin myImportOpac = null;
        ConfigOpacCatalogue coc = null;

        try {
            String catalogue = replacer.replace(configCatalogue);
            for (ConfigOpacCatalogue configOpacCatalogue : ConfigOpac.getInstance().getAllCatalogues(processTemplateName)) {
                if (configOpacCatalogue.getTitle().equals(catalogue)) {
                    myImportOpac = configOpacCatalogue.getOpacPlugin();
                    coc = configOpacCatalogue;
                }
            }
            if (myImportOpac == null) {
                if (configIgnoreMissingData) {
                    log.debug(
                            "Opac plugin for catalogue " + catalogue + " not found. No automatic catalogue request possible. Move on with workflow.");
                    Helper.setMeldung("No catalogue identifier found. No automatic catalogue request possible.");
                    Helper.addMessageToProcessLog(step.getProzess().getId(), LogType.INFO,
                            "Opac plugin for catalogue " + catalogue + " not found. No automatic catalogue request possible. Move on with workflow.");
                    return PluginReturnValue.FINISH;
                } else {
                    log.error("Opac plugin for catalogue " + catalogue + " not found. No automatic catalogue request possible.");
                    Helper.setFehlerMeldung("No catalogue identifier found. No automatic catalogue request possible.");
                    Helper.addMessageToProcessLog(step.getProzess().getId(), LogType.ERROR,
                            "Opac plugin for catalogue " + catalogue + " not found. No automatic catalogue request possible.");
                    return PluginReturnValue.ERROR;
                }
            }
            if (myImportOpac.getTitle().equals("intranda_opac_json")) {

                /**
                JsonOpacPlugin jsonOpacPlugin = (JsonOpacPlugin) myImportOpac;
                de.intranda.goobi.plugins.util.Config jsonOpacConfig = jsonOpacPlugin.getConfigForOpac();
                for (StringPair sp : valueList) {
                    for (SearchField sf : jsonOpacConfig.getFieldList()) {
                        if ((sf.getId()).equals(sp.getOne())) {
                            String value = sp.getTwo();
                            if (StringUtils.isNotBlank(value)) {
                                sf.setText(value);
                                sf.setSelectedField(sp.getOne());
                            }
                        }
                    }
                }
                 Direct access to the classes is not possible because of different class loaders.
                 Replace code above with reflections:
                 */

                try {
                    Class<? extends Object> opacClass = myImportOpac.getClass();
                    Method getConfigForOpac = opacClass.getMethod("getConfigForOpac");
                    Object jsonOpacConfig = getConfigForOpac.invoke(myImportOpac);

                    Class<? extends Object> jsonOpacConfigClass = jsonOpacConfig.getClass();

                    Method getFieldList = jsonOpacConfigClass.getMethod("getFieldList");

                    Object fieldList = getFieldList.invoke(jsonOpacConfig);
                    List<Object> searchfields =  (List<Object>) fieldList;
                    for (StringPair sp : valueList) {
                        for (Object searchField : searchfields) {
                            Class<? extends Object> searchFieldClass = searchField.getClass();

                            Method getId = searchFieldClass.getMethod("getId");

                            Method setText = searchFieldClass.getMethod("setText", String.class);
                            Method setSelectedField = searchFieldClass.getMethod("setSelectedField", String.class);

                            Object id = getId.invoke(searchField);
                            if (((String) id).equals(sp.getOne())) {
                                String value = sp.getTwo();
                                if (StringUtils.isNotBlank(value)) {
                                    setText.invoke(searchField, value);
                                    setSelectedField.invoke(searchField, sp.getOne());
                                }
                            }
                        }

                    }
                    Method search = opacClass.getMethod("search", String.class, String.class, ConfigOpacCatalogue.class, Prefs.class);

                    ffNew = (Fileformat)  search.invoke(myImportOpac, "","",coc, prefs);

                } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                    return null;
                }




            } else {
                ffNew = myImportOpac.search(valueList.get(0).getOne(), valueList.get(0).getTwo(), coc, prefs);
            }
        } catch (Exception e) {
            log.error("Exception while requesting the catalogue", e);
            Helper.setFehlerMeldung("Exception while requesting the catalogue", e);
            return PluginReturnValue.ERROR;
        }

        if (ffNew == null) {
            if (configIgnoreRequestIssues) {
                log.debug("No record found. No automatic catalogue request possible. Move on with workflow.");
                Helper.setMeldung("No record found. No automatic catalogue request possible.");
                Helper.addMessageToProcessLog(step.getProzess().getId(), LogType.INFO,
                        "No record found. No automatic catalogue request possible. Move on with workflow.");
                return PluginReturnValue.FINISH;
            } else {
                log.error("No record found. No automatic catalogue request possible.");
                Helper.setFehlerMeldung("No record found. No automatic catalogue request possible.");
                Helper.addMessageToProcessLog(step.getProzess().getId(), LogType.ERROR, "No record found. No automatic catalogue request possible.");
                return PluginReturnValue.ERROR;
            }
        }

        // if structure subelements shall be kept, merge old and new fileformat, otherwise just write the new one
        try {
            if (configMergeRecords) {
                // first load logical topstruct or first child

                DocStruct topstructNew = ffNew.getDigitalDocument().getLogicalDocStruct();
                DocStruct anchorNew = null;
                DocStruct physNew = ffNew.getDigitalDocument().getPhysicalDocStruct();
                if (topstructNew.getType().isAnchor()) {
                    anchorNew = topstructNew;
                    topstructNew = topstructNew.getAllChildren().get(0);
                }
                // uncommented doctype change for adw
                // topstructOld.setType(topstructNew.getType());

                // run through all old metadata of main element
                mergeMetadataRecords(topstructOld, topstructNew);

                // replace metadata of anchor element
                if (anchorNew != null && anchorOld != null) {
                    mergeMetadataRecords(anchorOld, anchorNew);
                }

                // replace metadata of physical element
                if (physOld != null && physOld != null) {
                    mergeMetadataRecords(physOld, physNew);
                }

                if (configAnalyseSubElements) {
                    List<DocStruct> dsl = topstructOld.getAllChildren();
                    if (dsl != null) {
                        MetadataType type = prefs.getMetadataTypeByName(configuredFields.get(0)
                                .getTwo()
                                .replace("$", "")
                                .replace("meta.", "")
                                .replace("topstruct.", "")
                                .replace("firstchild.", "")
                                .replace("(", "")
                                .replace("{", "")
                                .replace("}", "")
                                .replace(")", ""));
                        for (DocStruct ds : dsl) {
                            getMetadataForChild(configSkipFields, prefs, myImportOpac, coc, type, ds);
                        }
                    }
                }

                // then write the updated old file format
                // ffOld.write(p.getMetadataFilePath());
                process.writeMetadataFile(ffOld);

            } else {
                // just write the new one and don't merge any data
                // ffNew.write(p.getMetadataFilePath());
                process.writeMetadataFile(ffNew);
            }
        } catch (Exception e) {
            log.error("Exception while writing the updated METS file into the file system", e);
            Helper.setFehlerMeldung("Exception while writing the updated METS file into the file system", e);
            return PluginReturnValue.ERROR;
        }

        // everything finished, exit plugin
        log.debug("Finished with catalogue request");
        return PluginReturnValue.FINISH;
    }

    /**
     * Replaces the metadata of the old docstruct with the values of the new docstruct. If a metadata type of the old docstruct is marked as to skip,
     * it gets not replaced. Otherwise all old data is removed and all new metadata is added.
     * 
     * @param docstructOld
     * @param docstructNew
     * @throws MetadataTypeNotAllowedException
     */

    private void mergeMetadataRecords(DocStruct docstructOld, DocStruct docstructNew) throws MetadataTypeNotAllowedException {
        if (docstructOld.getAllMetadata() != null) {
            List<Metadata> metadataToRemove = new ArrayList<>();
            for (Metadata md : docstructOld.getAllMetadata()) {
                // check if they should be replaced or skipped
                if (!configSkipFields.contains(md.getType().getName())) {
                    metadataToRemove.add(md);
                }
            }
            // remove old entry
            for (Metadata md : metadataToRemove) {
                docstructOld.removeMetadata(md, true);
            }
        }

        // add new metadata
        if (docstructNew.getAllMetadata() != null) {
            for (Metadata md : docstructNew.getAllMetadata()) {
                if (!configSkipFields.contains(md.getType().getName())) {
                    Metadata newmetadata = new Metadata(md.getType());
                    newmetadata.setValue(md.getValue());
                    docstructOld.addMetadata(newmetadata);
                }
            }
        }
        if (docstructOld.getAllPersons() != null) {
            List<Person> personsToRemove = new ArrayList<>();
            for (Person pd : docstructOld.getAllPersons()) {
                if (!configSkipFields.contains(pd.getType().getName())) {
                    personsToRemove.add(pd);
                }
            }
            for (Person pd : personsToRemove) {
                docstructOld.removePerson(pd, true);
            }
        }
        if (docstructNew.getAllPersons() != null) {
            // now add the new persons to the old topstruct
            for (Person pd : docstructNew.getAllPersons()) {
                if (!configSkipFields.contains(pd.getType().getName())) {
                    docstructOld.addPerson(pd);
                }
            }
        }

        // corporates
        List<Corporate> allCorporates = new ArrayList<>();
        if (docstructOld.getAllCorporates() != null) {
            allCorporates = new ArrayList<>(docstructOld.getAllCorporates());
        }
        for (Corporate corporate : allCorporates) {
            if (!configSkipFields.contains(corporate.getType().getName())) {
                List<? extends Corporate> remove = docstructOld.getAllCorporatesByType(corporate.getType());
                if (remove != null) {
                    for (Corporate pdRm : remove) {
                        docstructOld.removeCorporate(pdRm);
                    }
                }
            }
        }
        if (docstructNew.getAllCorporates() != null) {
            // now add the new persons to the old topstruct
            for (Corporate pd : docstructNew.getAllCorporates()) {
                if (!configSkipFields.contains(pd.getType().getName())) {
                    try {
                        docstructOld.addCorporate(pd);
                    } catch (MetadataTypeNotAllowedException | IncompletePersonObjectException e) {
                        // ignore metadata not allowed errors
                    }
                }
            }
        }

        if (docstructOld.getAllMetadataGroups() != null) {
            List<MetadataGroup> groupsToRemove = new ArrayList<>();

            for (MetadataGroup group : docstructOld.getAllMetadataGroups()) {
                // check if the group should be skipped
                if (!configSkipFields.contains(group.getType().getName())) {
                    // if not, remove the old groups of the type
                    groupsToRemove.add(group);
                }
            }
            for (MetadataGroup group : groupsToRemove) {
                docstructOld.removeMetadataGroup(group, true);
            }
        }
        // add new metadata groups
        if (docstructNew.getAllMetadataGroups() != null) {
            for (MetadataGroup newGroup : docstructNew.getAllMetadataGroups()) {
                if (!configSkipFields.contains(newGroup.getType().getName())) {
                    docstructOld.addMetadataGroup(newGroup);
                }
            }
        }
    }

    /**
     * execute method that is clicked by the user or executed by Goobi automatically
     */
    @Override
    public boolean execute() {
        PluginReturnValue check = run();
        if (check.equals(PluginReturnValue.FINISH)) {
            return true;
        }
        return false;
    }

    @Override
    public String cancel() {
        return returnPath;
    }

    /**
     * Initialize the plugin with all relevant information from the configuration file
     */
    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.returnPath = returnPath;
        process = step.getProzess();
        prefs = process.getRegelsatz().getPreferences();

        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        configCatalogue = myconfig.getString("catalogue", "GBV").trim();
        configuredFields = new ArrayList<>();
        List<HierarchicalConfiguration> fields = myconfig.configurationsAt("catalogueField");
        for (HierarchicalConfiguration field : fields) {
            String fieldname = field.getString("@fieldName");
            String metadataName = field.getString("@fieldValue");
            configuredFields.add(new StringPair(fieldname, metadataName));
        }
        if (configuredFields.isEmpty()) {
            configuredFields.add(new StringPair("12", "$(meta.CatalogIDDigital)"));
        }

        configMergeRecords = myconfig.getBoolean("mergeRecords", false);
        configAnalyseSubElements = myconfig.getBoolean("analyseSubElements", false);
        configIgnoreRequestIssues = myconfig.getBoolean("ignoreRequestIssues", false);
        configIgnoreMissingData = myconfig.getBoolean("ignoreMissingData", false);
        configSkipFields = Arrays.asList(myconfig.getStringArray("skipField"));
    }

    @Override
    public String finish() {
        return returnPath;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public int getInterfaceVersion() {
        return 2;
    }

    /**
     * Get the value for a metadata type from a docstruct. Possible return values are:
     * <ul>
     * <li>null if docstruct or metadata type are undefined</li>
     * <li>empty string, if docstruct does not contain the metadata</li>
     * <li>value of the first occurrence of the metadata</li>
     * </ul>
     * 
     * @param docstruct
     * @param metadataType
     * @return
     */

    protected String getMetadataValueFromDoctStruct(DocStruct docstruct, MetadataType metadataType) {
        if (docstruct == null || metadataType == null) {
            return null;
        }
        for (Metadata md : docstruct.getAllMetadataByType(metadataType)) {
            return md.getValue();
        }

        return "";
    }

    private void getMetadataForChild(List<String> configSkipFields, Prefs prefs, IOpacPlugin myImportOpac, ConfigOpacCatalogue coc, MetadataType type,
            DocStruct ds) throws Exception, PreferencesException {
        List<? extends Metadata> identifierList = ds.getAllMetadataByType(type);
        if (identifierList != null && !identifierList.isEmpty()) {
            String identifier = identifierList.get(0).getValue();
            Fileformat ff = myImportOpac.search("12", identifier, coc, prefs);
            mergeMetadataRecords(ds, ff.getDigitalDocument().getLogicalDocStruct());
        }
        List<DocStruct> children = ds.getAllChildren();
        if (children != null && !children.isEmpty()) {
            for (DocStruct child : children) {
                getMetadataForChild(configSkipFields, prefs, myImportOpac, coc, type, child);
            }
        }
    }
}
