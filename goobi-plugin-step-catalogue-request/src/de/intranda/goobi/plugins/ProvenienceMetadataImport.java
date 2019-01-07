package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.jdom2.Element;

import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.EqualsAndHashCode;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataGroupType;
import ugh.dl.MetadataType;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

//@Data

@PluginImplementation
@EqualsAndHashCode(callSuper = false)
@Log4j
public class ProvenienceMetadataImport extends CatalogueRequestPlugin {

    private String title = "intranda_step_provenience_request";

    private MetadataGroupType groupType;
    private MetadataType provenienceCode;
    private MetadataType proveniencePrevOwner;
    private MetadataType provenienceCharacteristic;
    private MetadataType provenienceDate;
    private MetadataType provenienceExplanation;
    private MetadataType provenienceGND;

    @Override
    public PluginReturnValue run() {
        Fileformat fileformat = null;
        DocStruct anchor = null;
        DocStruct topStruct = null;
        MetadataType epnType = prefs.getMetadataTypeByName("CatalogIDSource");
        try {
            fileformat = process.readMetadataFile();
            topStruct = fileformat.getDigitalDocument().getLogicalDocStruct();
            if (topStruct.getType().isAnchor()) {
                anchor = topStruct;
                topStruct = topStruct.getAllChildren().get(0);
            }
        } catch (ReadException | PreferencesException | WriteException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }
        IOpacPlugin opacPlugin = (IOpacPlugin) PluginLoader.getPluginByTitle(PluginType.Opac, "HaabProvenienceOpac");

        groupType = prefs.getMetadataGroupTypeByName("Provenience");

        provenienceCode = prefs.getMetadataTypeByName("ProvenienceCode");
        proveniencePrevOwner = prefs.getMetadataTypeByName("ProveniencePrevOwner");
        provenienceCharacteristic = prefs.getMetadataTypeByName("ProvenienceCharacteristic");
        provenienceDate = prefs.getMetadataTypeByName("ProvenienceDate");
        provenienceExplanation = prefs.getMetadataTypeByName("ProvenienceExplanation");
        provenienceGND = prefs.getMetadataTypeByName("ProvenienceGND");
        if (anchor != null) {
            List<MetadataGroup> oldGroups = anchor.getAllMetadataGroupsByType(groupType);
            for (MetadataGroup mg : oldGroups) {
                anchor.removeMetadataGroup(mg);
            }
            String epn = getMetadataValueFromDoctStruct(anchor, epnType);
            // use first epn if more than one is given
            if (StringUtils.isNotBlank(epn) && epn.contains(" ")) {
                epn = epn.substring(0, epn.indexOf(" "));
            }

            Element element = getPicaRecord(opacPlugin, epn);
            if (element != null) {
                try {
                    List<MetadataGroup> proveniences = getDataFromPicaRecord(element, epn);
                    for (MetadataGroup grp : proveniences) {
                        anchor.addMetadataGroup(grp);
                    }
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                    return PluginReturnValue.ERROR;
                }
            }

        }

        if (topStruct != null) {

            List<MetadataGroup> oldGroups = topStruct.getAllMetadataGroupsByType(groupType);
            for (MetadataGroup mg : oldGroups) {
                topStruct.removeMetadataGroup(mg);
            }

            String epn = getMetadataValueFromDoctStruct(topStruct, epnType);
            Element element = getPicaRecord(opacPlugin, epn);
            if (element != null) {
                try {
                    List<MetadataGroup> proveniences = getDataFromPicaRecord(element, epn);
                    for (MetadataGroup grp : proveniences) {
                        topStruct.addMetadataGroup(grp);
                    }
                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                    return PluginReturnValue.ERROR;
                }
            }
        }
        try {
            process.writeMetadataFile(fileformat);
        } catch (WriteException | PreferencesException | IOException | InterruptedException | SwapException | DAOException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }

    private List<MetadataGroup> getDataFromPicaRecord(Element pica, String epn) throws MetadataTypeNotAllowedException {
        List<MetadataGroup> groups = new ArrayList<>();
        for (Element datafield : pica.getChildren()) {
            if ("092B".equals(datafield.getAttributeValue("tag"))) {
                // found possible provenience, check for values of subfields in code 1 and 2
                String codeSigil = null;
                String codeEpn = null;
                for (Element subfield : datafield.getChildren()) {
                    if ("1".equals(subfield.getAttributeValue("code"))) {
                        codeSigil = subfield.getValue();
                    } else if ("2".equals(subfield.getAttributeValue("code"))) {
                        codeEpn = subfield.getValue();
                    }
                }

                if ("0032".equals(codeSigil) && epn.equals(codeEpn)) {
                    // found new provenience entry, create new MetadataGroup
                    MetadataGroup provGroup = new MetadataGroup(groupType);
                    groups.add(provGroup);
                    boolean foundSubfieldZero = false;
                    for (Element subfield : datafield.getChildren()) {
                        switch (subfield.getAttributeValue("code")) {
                            case "S":
                                addSubfieldToGroup(provGroup, subfield, provenienceCode);
                                break;
                            case "a":
                                addSubfieldToGroup(provGroup, subfield, proveniencePrevOwner);
                                break;
                            case "0":
                            case "8":
                                foundSubfieldZero = true;
                                String value = subfield.getValue().replace("gnd/", "");
                                Metadata owner = provGroup.getMetadataByType(proveniencePrevOwner.getName()).get(0);
                                owner.setAutorityFile("gnd", "http://d-nb.info/gnd/", value);
                                //                                PrevOwner normdata
                                break;
                            case "b":
                                if (foundSubfieldZero) {
                                    addSubfieldToGroup(provGroup, subfield, provenienceCharacteristic);
                                }
                                break;
                            case "c":
                                // check for numeric values to ignore name additions like "von"
                                if (subfield.getValue().matches(".*\\d+.*")) {
                                    addSubfieldToGroup(provGroup, subfield, provenienceDate);
                                }
                                break;
                            case "k":
                                addSubfieldToGroup(provGroup, subfield, provenienceExplanation);
                                break;
                            case "6":
                                addSubfieldToGroup(provGroup, subfield, provenienceGND);
                                break;
                        }
                    }

                }

            }
        }

        return groups;
    }

    private void addSubfieldToGroup(MetadataGroup provGroup, Element subfield, MetadataType type) throws MetadataTypeNotAllowedException {
        List<Metadata> metadata = provGroup.getMetadataByType(type.getName());
        boolean matched = false;
        for (Metadata md : metadata) {
            if (StringUtils.isBlank(md.getValue())) {
                md.setValue(subfield.getValue());
                matched = true;
                break;
            }
        }
        if (!matched) {
            Metadata md = new Metadata(type);
            md.setValue(subfield.getValue());
            provGroup.addMetadata(md);
        }
    }

    private Element getPicaRecord(IOpacPlugin opacPlugin, String epn) {
        try {
            opacPlugin.search(null, epn, null, null);
            java.lang.reflect.Method method;

            method = opacPlugin.getClass().getMethod("getPicaRecord");
            Object o = method.invoke(opacPlugin);
            if (o != null) {
                Element element = (Element) o;
                return element;
            }
        } catch (Exception e) {
            log.error(e);
        }
        return null;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        this.returnPath = returnPath;
        process = step.getProzess();
        prefs = process.getRegelsatz().getPreferences();
    }
}
