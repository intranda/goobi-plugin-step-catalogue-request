package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;

@PluginImplementation
@Log4j
@EqualsAndHashCode(callSuper = false)
public @Data class CatalogueRequestPlugin implements IStepPluginVersion2 {

    private PluginGuiType pluginGuiType = PluginGuiType.NONE;
    private PluginType type = PluginType.Step;

    private String title = "intranda_step_catalogue_request";

    private String pagePath = "";
    private Step step;
    private String returnPath;
    
    private String configCatalogue = "";
    private String configCatalogueId = "";
    private boolean configMergeRecords = false;
    private List<String> configSkipFields = null;
    
    /** 
     * run this plugin and execute the catalogue update
     */
    @Override
    public PluginReturnValue run() {
    		log.debug("Starting catalogue request using catalogue: " + configCatalogue + " with identifier field " + configCatalogueId);
    		
    		// first read the original METS file for the process
    		Fileformat ffOld = null;
    		DigitalDocument dd = null;
        Process p = step.getProzess();
        Prefs prefs = p.getRegelsatz().getPreferences();
        DocStruct topstructOld = null;
        try {
        		ffOld = p.readMetadataFile();
            if (ffOld == null) {
                	log.error("Metadata file is not readable for process with ID " + step.getProcessId());
                	Helper.setFehlerMeldung("Metadata file is not readable for process with ID " + step.getProcessId());
                	return PluginReturnValue.ERROR;
            }
            dd = ffOld.getDigitalDocument();
            topstructOld = ffOld.getDigitalDocument().getLogicalDocStruct();
			if (topstructOld.getType().isAnchor()) {
				topstructOld = topstructOld.getAllChildren().get(0);
			}
        } catch (Exception e) {
        		log.error("An exception occurred while reading the metadata file for process with ID " + step.getProcessId(), e);
        		Helper.setFehlerMeldung("An exception occurred while reading the metadata file for process with ID " + step.getProcessId(), e);
            	return PluginReturnValue.ERROR;
        }
        
        // create a VariableReplacer to transform the identifier field from the configuration into a real value
        VariableReplacer replacer = new VariableReplacer(dd, prefs, step.getProzess(), step);
        String catalogueId = replacer.replace(configCatalogueId);
    		log.debug("Using this value for the catalogue request: " + catalogueId);
    		
    		// request the wished catalogue with the correct identifier
    		Fileformat ffNew = null;
		try {
			ConfigOpacCatalogue coc = ConfigOpac.getInstance().getCatalogueByName(configCatalogue);
			IOpacPlugin myImportOpac = (IOpacPlugin) PluginLoader.getPluginByTitle(PluginType.Opac, coc.getOpacType());
			ffNew = myImportOpac.search("12", catalogueId, coc, prefs);
		} catch (Exception e) {
			log.error("Exception while requesting the catalogue", e);
			Helper.setFehlerMeldung("Exception while requesting the catalogue", e);
			return PluginReturnValue.ERROR;
		}
		
		// if structure subelements shall be kept, merge old and new fileformat, otherwise just write the new one
		try {
			if (configMergeRecords) {
				// first load logical topstruct or first child
				DocStruct topstructNew = ffNew.getDigitalDocument().getLogicalDocStruct();
				if (topstructNew.getType().isAnchor()) {
					topstructNew = topstructNew.getAllChildren().get(0);
				}
				
				// then run through all new metadata and check if these should
                // replace the old ones
                // if yes remove the old ones from the old fileformat
                if (topstructNew.getAllMetadata() != null) {
                    for (Metadata md : topstructNew.getAllMetadata()) {
                        if (!configSkipFields.contains(md.getType().getName())) {
                            List<? extends Metadata> remove = topstructOld.getAllMetadataByType(md.getType());
                            if (remove != null) {
                                for (Metadata mdRm : remove) {
                                    topstructOld.removeMetadata(mdRm);
                                }
                            }
                        }
                    }
                    // now add the new metadata to the old topstruct
                    for (Metadata md : topstructNew.getAllMetadata()) {
                        if (!configSkipFields.contains(md.getType().getName())) {
                            topstructOld.addMetadata(md);
                        }
                    }
                }

                // now do the same with persons
                if (topstructNew.getAllPersons() != null) {
                    for (Person pd : topstructNew.getAllPersons()) {
                        if (!configSkipFields.contains(pd.getType().getName())) {
                            List<? extends Person> remove = topstructOld.getAllPersonsByType(pd.getType());
                            if (remove != null) {
                                for (Person pdRm : remove) {
                                    topstructOld.removePerson(pdRm);
                                }
                            }
                        }
                    }
                    // now add the new persons to the old topstruct
                    for (Person pd : topstructNew.getAllPersons()) {
                        if (!configSkipFields.contains(pd.getType().getName())) {
                            topstructOld.addPerson(pd);
                        }
                    }
                }
				
				// then write the updated old file format
				ffOld.write(p.getMetadataFilePath());
				
			} else {
				// just write the new one and don't merge any data
				ffNew.write(p.getMetadataFilePath());
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

        String projectName = step.getProzess().getProjekt().getTitel();
		XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
		xmlConfig.setExpressionEngine(new XPathExpressionEngine());
		
		SubnodeConfiguration myconfig = null;

		// order of configuration is:
		// 1.) project name and step name matches
		// 2.) step name matches and project is *
		// 3.) project name matches and step name is *
		// 4.) project name and step name are *
		try {
			myconfig = xmlConfig
					.configurationAt("//config[./project = '" + projectName + "'][./step = '" + step.getTitel() + "']");
		} catch (IllegalArgumentException e) {
			try {
				myconfig = xmlConfig.configurationAt("//config[./project = '*'][./step = '" + step.getTitel() + "']");
			} catch (IllegalArgumentException e1) {
				try {
					myconfig = xmlConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
				} catch (IllegalArgumentException e2) {
					myconfig = xmlConfig.configurationAt("//config[./project = '*'][./step = '*']");
				}
			}
		}

		configCatalogue = myconfig.getString("catalogue", "GBV");
		configCatalogueId = myconfig.getString("catalogueIdentifier", "-");
		configMergeRecords = myconfig.getBoolean("mergeRecords", false);
		configSkipFields = myconfig.getList("skipField", new ArrayList<>());
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

}
