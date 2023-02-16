package org.labkey.variantdb.analysis.Imputation;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Logger;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.reader.Readers;
import org.labkey.api.util.Job;
import org.labkey.api.writer.PrintWriters;
import org.labkey.variantdb.run.GigiRunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Created by bimber on 5/9/2016.
 */
public class GiGiJob extends Job
{
    private final Logger _log;
    private final String _chr;
    private final Integer _denseMarkerIdx;
    private final File _outputDir;
    private final File _alleleFreqDir;
    private final String _callMethod;
    private final File _ivFile;
    private final File _glAutoBaseDir;
    private final int _sampledIVs;

    public GiGiJob(Logger log, String chr, Integer denseMarkerIdx, File outputDir, File alleleFreqDir, String callMethod, File ivFile, File glAutoBaseDir, int sampledIVs)
    {
        _log = log;
        _chr = chr;
        _denseMarkerIdx = denseMarkerIdx;
        _outputDir = outputDir;
        _glAutoBaseDir = glAutoBaseDir;
        _alleleFreqDir = alleleFreqDir;
        _callMethod = callMethod;
        _ivFile = ivFile;
        _sampledIVs = sampledIVs;
    }

    @Override
    public void run()
    {
        try
        {
            GigiRunner gigi = new GigiRunner(_log);
            File gigiParams = new File(_outputDir, "gigi-" + _denseMarkerIdx + ".par");
            try (PrintWriter paramWriter = PrintWriters.getPrintWriter(gigiParams))
            {
                File orderedPed = new File(_glAutoBaseDir, "ordered.ped");
                if (orderedPed.exists())
                {
                    _log.info("using ordered.ped file created by gl_auto");
                    File ped = preparePedigreeForGigi(orderedPed);
                    paramWriter.write(ped.getPath() + '\n');
                }
                else
                {
                    File morganPed = new File(_outputDir, "../../../morgan.ped");
                    if (!morganPed.exists())
                    {
                        throw new PipelineJobException("File does not exist: " + morganPed);
                    }

                    paramWriter.write(morganPed.getPath() + '\n');
                }
                paramWriter.write(_ivFile.getPath() + '\n');
                paramWriter.write(_sampledIVs + "\n");
                paramWriter.write(new File(_glAutoBaseDir, ImputationFileUtil.MarkerType.framework.name() + "_map.txt").getPath() + '\n');
                paramWriter.write(new File(_glAutoBaseDir, ImputationFileUtil.MarkerType.dense.name() + "-" + _denseMarkerIdx + "_map.txt").getPath() + '\n');
                paramWriter.write(new File(_glAutoBaseDir, ImputationFileUtil.MarkerType.dense.name() + "-" + _denseMarkerIdx + ".gigi.geno").getPath() + '\n');

                File afreq = ImputationFileUtil.getAlleleFreqFile(_alleleFreqDir, ImputationFileUtil.MarkerType.dense, _chr, _denseMarkerIdx);
                paramWriter.write(afreq.getPath() + '\n');
                paramWriter.write(_callMethod);
            }

            gigi.execute(gigiParams, gigiParams.getParentFile());

            for (String ext : Arrays.asList("geno", "prob", "consistentIV"))
            {
                File toRename = new File(_outputDir, "impute." + ext);
                if (toRename.exists())
                {
                    _log.info("renaming GIGI output: " + toRename.getPath());
                    File imputeOutput = new File(_outputDir, "impute-" + _denseMarkerIdx + "." + ext);
                    if (imputeOutput.exists())
                    {
                        _log.warn("deleting existing GIGI output: " + imputeOutput.getPath());
                        imputeOutput.delete();
                    }
                    FileUtils.moveFile(toRename, imputeOutput);
                }
            }
        }
        catch (IOException | PipelineJobException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * If we supply an input pedigree that is not sorted as GL_AUTO expects, it will output a new ordered.ped file.
     * Unfortunately, this pedigree doesnt conform to what GIGI expects, so we need to tweak the header here
     */
    private File preparePedigreeForGigi(File pedigree) throws IOException
    {
        File output = new File(pedigree.getParent(), "orderedGigi.ped");
        if (output.exists())
        {
            return output;
        }

        try (BufferedReader reader = Readers.getReader(pedigree);PrintWriter writer = PrintWriters.getPrintWriter(output))
        {
            boolean inHeader = true;
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null)
            {
                lineNo++;
                if (inHeader)
                {
                    if (lineNo <= 3)
                    {
                        writer.write(line + '\n');
                    }

                    if (line.startsWith("*"))
                    {
                        inHeader = false;
                        writer.write(line + '\n');
                    }
                }
                else
                {
                    writer.write(line + '\n');
                }
            }
        }

        return output;
    }

    @Override
    protected void done(Throwable t)
    {
        if (null != t)
            _log.error("Uncaught exception in prepare genotype files job: " + getId(), t);
    }

    private String getId()
    {
        return _chr + " " + _denseMarkerIdx;
    }
}
