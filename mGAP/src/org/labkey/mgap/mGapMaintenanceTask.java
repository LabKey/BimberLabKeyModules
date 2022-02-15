package org.labkey.mgap;

import org.apache.logging.log4j.Logger;
import org.labkey.api.data.Container;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableSelector;
import org.labkey.api.ldk.LDKService;
import org.labkey.api.pipeline.PipeRoot;
import org.labkey.api.pipeline.PipelineService;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.SystemMaintenance;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class mGapMaintenanceTask implements SystemMaintenance.MaintenanceTask
{
    @Override
    public String getDescription()
    {
        return "Deletes unused mGAP VCFs and ETL artifacts";
    }

    @Override
    public String getName()
    {
        return "mGAP Maintenance Task";
    }

    @Override
    public void run(Logger log)
    {
        Container c = mGAPManager.get().getMGapContainer();
        if (c == null)
        {
            return;
        }

        PipeRoot pr = PipelineService.get().getPipelineRootSetting(c);
        if (!pr.getRootPath().exists())
        {
            return;
        }

        File baseDir = new File(pr.getRootPath(), mGAPManager.DATA_DIR_NAME);
        if (!baseDir.exists())
        {
            return;
        }

        User u = LDKService.get().getBackgroundAdminUser();
        if (u == null)
        {
            log.error("LDK Background user not set, cannot run mGAP Maintenance task");
            return;
        }

        // Find expected folder names:
        List<String> releaseIds = new TableSelector(QueryService.get().getUserSchema(u, c, mGAPSchema.NAME).getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES), PageFlowUtil.set("objectid")).getArrayList(String.class);

        Set<File> toDelete = new HashSet<>();

        File[] dirs = baseDir.listFiles((dir, name) -> dir.isDirectory());
        List<String> existingFolderNames = dirs == null ? Collections.emptyList() : Arrays.stream(dirs).map(File::getName).collect(Collectors.toList());
        List<String> unexpectedDirs = new ArrayList<>(existingFolderNames);
        unexpectedDirs.removeAll(releaseIds);
        for (String dirName : unexpectedDirs)
        {
            log.error("Unexpected directory present: " + dirName);
            toDelete.add(new File(baseDir, dirName));
        }

        List<String> missingDirs = new ArrayList<>(releaseIds);
        missingDirs.removeAll(existingFolderNames);
        for (String dirName : missingDirs)
        {
            log.error("Missing expected directory: " + dirName);
        }

        releaseIds.forEach(f -> inspectReleaseFolder(f, baseDir, c, u, log, toDelete));
    }

    private void inspectReleaseFolder(String releaseId, File baseDir, Container c, User u, final Logger log, final Set<File> toDelete)
    {
        File releaseDir = new File(baseDir, releaseId);
        if (!releaseDir.exists())
        {
            log.error("Missing folder: " + releaseDir.getPath());
            return;
        }

        final Set<File> expectedFiles = new HashSet<>();
        List<Integer> tracksFromRelease = new TableSelector(QueryService.get().getUserSchema(u, c, mGAPSchema.NAME).getTable(mGAPSchema.TABLE_TRACKS_PER_RELEASE), PageFlowUtil.set("vcfId"), new SimpleFilter(FieldKey.fromString("releaseId"), releaseId), null).getArrayList(Integer.class);
        tracksFromRelease.forEach(rowId -> {
            SequenceOutputFile so = SequenceOutputFile.getForId(rowId);
            if (so == null)
            {
                log.error("Unknown output file in tracksPerRelease: " + rowId + " for release: " + releaseId);
                return;
            }

            File f = so.getFile();
            if (f == null)
            {
                log.error("No file for outputfile: " + rowId + " for release: " + releaseId);
                return;
            }

            expectedFiles.add(f);
            expectedFiles.add(new File(f.getPath() + ".tbi"));
        });

        final Set<String> fields = PageFlowUtil.set("vcfId", "variantTable", "liftedVcfId", "sitesOnlyVcfId");
        new TableSelector(QueryService.get().getUserSchema(u, c, mGAPSchema.NAME).getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES), fields, new SimpleFilter(FieldKey.fromString("objectid"), releaseId), null).forEachResults(rs -> {
            for (String field : fields)
            {
                if (rs.getObject(FieldKey.fromString(field)) == null)
                {
                    continue;
                }

                int rowId = rs.getInt(FieldKey.fromString(field));
                SequenceOutputFile so = SequenceOutputFile.getForId(rowId);
                if (so == null)
                {
                    log.error("Unknown output file: " + rowId + " for field: " + field + ", for variant release: " + releaseId);
                    return;
                }

                File f = so.getFile();
                if (f == null)
                {
                    log.error("No file for outputfile: " + rowId + " for release: " + releaseId);
                    return;
                }

                expectedFiles.add(f);
                if (f.getPath().toLowerCase().endsWith("vcf.gz"))
                {
                    expectedFiles.add(new File(f.getPath() + ".tbi"));
                }
            }
        });

        File[] files = releaseDir.listFiles();
        List<File> filesPresent = files == null ? Collections.emptyList() : Arrays.asList(files);

        List<File> unexpectedFiles = new ArrayList<>(filesPresent);
        unexpectedFiles.removeAll(expectedFiles);
        for (File f : unexpectedFiles)
        {
            log.error("Unexpected file present: " + f.getPath());
            toDelete.add(f);
        }

        List<File> missingFiles = new ArrayList<>(expectedFiles);
        missingFiles.removeAll(filesPresent);
        for (File f : missingFiles)
        {
            log.error("Missing expected file: " + f.getPath());
        }
    }
}
