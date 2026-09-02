package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

public class CatalogueRequestPluginTest {

    private static final String RULESET = "src/test/resources/samples/baggesen/baggesen.xml";
    private static final String RECORD = "src/test/resources/samples/baggesen/meta_3631791.xml";

    /**
     * The record's "Werk" genre already carries authority data (GND). When the catalogue request plugin re-imports the record and merges it
     * into the existing process metadata, the authority data must survive the update just like it does for persons.
     */
    @Test
    public void test_mergeMetadataRecords_keepsAuthorityDataForGenre() throws Exception {
        Prefs prefs = new Prefs();
        prefs.loadPrefs(RULESET);

        DocStruct dsOld = loadLogicalDocStruct(prefs);
        DocStruct dsNew = loadLogicalDocStruct(prefs);

        MetadataType genreType = prefs.getMetadataTypeByName("GenreType");
        assertNotNull(genreType);

        // simulate the catalogue having updated the genre in the meantime, including its authority data
        Metadata newGenre = dsNew.getAllMetadataByType(genreType)
                .stream()
                .filter(md -> "Werk".equals(md.getValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Sample record no longer contains a GenreType 'Werk'"));
        newGenre.setValue("Fragment");
        newGenre.setAuthorityFile("gnd", "https://d-nb.info/gnd/", "4154270-6");

        CatalogueRequestPlugin plugin = new CatalogueRequestPlugin();
        plugin.setPrefs(prefs);
        plugin.setConfigSkipFields(Collections.emptyList());
        plugin.setConfigIncludeFields(Collections.emptyList());

        plugin.mergeMetadataRecords(dsOld, dsNew);

        List<? extends Metadata> genresAfterMerge = dsOld.getAllMetadataByType(genreType);
        Metadata mergedGenre = genresAfterMerge.stream()
                .filter(md -> "Fragment".equals(md.getValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("GenreType 'Fragment' not found after merge. Found: " + genresAfterMerge));

        assertEquals("4154270-6", mergedGenre.getAuthorityValue());
        assertEquals("https://d-nb.info/gnd/", mergedGenre.getAuthorityURI());
        assertEquals("gnd", mergedGenre.getAuthorityID());
    }

    private DocStruct loadLogicalDocStruct(Prefs prefs) throws Exception {
        Fileformat ff = new MetsMods(prefs);
        ff.read(RECORD);
        DigitalDocument dd = ff.getDigitalDocument();
        return dd.getLogicalDocStruct();
    }

}
