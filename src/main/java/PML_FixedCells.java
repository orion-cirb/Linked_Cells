/*
 * Find Nucleus and count PML inside
 * Author Philippe Mailly
 */

import PML_FixedCells_Tools.Tools;
import ij.*;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.FileSaver;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.RGBStackMerge;
import java.awt.Rectangle;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.plugins.BF;
import loci.plugins.util.ImageProcessorReader;
import loci.plugins.in.ImporterOptions;
import mcib3d.geom2.BoundingBox;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.image3d.ImageHandler;
import org.apache.commons.io.FilenameUtils;
import org.scijava.util.ArrayUtils;



public class PML_FixedCells implements PlugIn {
    
    Tools tools = new Tools();
    
    private String imageDir = "";
    public String outDirResults = "";
    private boolean canceled = false;
   
    private BufferedWriter outPutResults;
    private BufferedWriter outPutDotsResults;
    
// Default Z step
    private final double zStep = 0.153;

    /**
     * 
     * @param arg
     */
    @Override
    public void run(String arg) {
        try {
            imageDir = IJ.getDirectory("Choose directory containing image files...");
            if (imageDir == null) {
                return;
            }   
            // Find images with file_ext extension
            String file_ext = tools.findImageType(new File(imageDir));
            ArrayList<String> imageFiles = tools.findImages(imageDir, file_ext);
            if (imageFiles == null) {
                IJ.showMessage("Error", "No images found with "+file_ext+" extension");
                return;
            }
            
            
            // create OME-XML metadata store of the latest schema version
            ServiceFactory factory;
            factory = new ServiceFactory();
            OMEXMLService service = factory.getInstance(OMEXMLService.class);
            IMetadata meta = service.createOMEXMLMetadata();
            ImageProcessorReader reader = new ImageProcessorReader();
            reader.setMetadataStore(meta);
            // Find channel names , calibration
            reader.setId(imageFiles.get(0));
            tools.cal = tools.findImageCalib(meta);
            String[] chsName = tools.findChannels(imageFiles.get(0), meta, reader);
            
            
            // Channels dialog
            
            String[] channels = tools.dialog(chsName);
            if ( channels == null || tools.canceled) {
                IJ.showStatus("Plugin cancelled");
                return;
            }
            
            // create output folder
            outDirResults = imageDir + File.separator+ "Results-"+tools.dotsDetection+"-"+tools.intFactor+ File.separator;
            File outDir = new File(outDirResults);
            if (!Files.exists(Paths.get(outDirResults))) {
                outDir.mkdir();
            }
            
            // Write headers results for results file
            FileWriter fileResults = null;
            String resultsName = "results_Int-"+tools.intFactor+".xls";
            try {
                fileResults = new FileWriter(outDirResults + resultsName, false);
            } catch (IOException ex) {
                Logger.getLogger(PML_FixedCells.class.getName()).log(Level.SEVERE, null, ex);
            }
            outPutResults = new BufferedWriter(fileResults);
            try {
                outPutResults.write("ImageName\t#Nucleus\tNucleus Volume\tPML dot number\tPML Diffuse IntDensity\tPML Mean dot IntDensity\tPML dot SD IntDensity"
                        + "\tPML dot Min IntDensity\tPML dot Max IntDensity\tPML dot Mean Volume\tPML dot SD Volume\tPML Min Vol\tPML Max Vol\tPML Sum Vol\tPML dot Mean center-center distance"
                        + "\tPML dot SD center-center distance\tPML dot Mean center-Nucleus center distance\tPML dot SD center-Nucleus center distance\tF SDI\n");
                outPutResults.flush();
            } catch (IOException ex) {
                Logger.getLogger(PML_FixedCells.class.getName()).log(Level.SEVERE, null, ex);
            }
            
            // write headers for dots results
            FileWriter fileDotsResults = null;
            String dotsResultsName = "dotsResults_Int-"+tools.intFactor+".xls";
            try {
                fileDotsResults = new FileWriter(outDirResults + dotsResultsName, false);
            } catch (IOException ex) {
                Logger.getLogger(PML_FixedCells.class.getName()).log(Level.SEVERE, null, ex);
            }
            outPutDotsResults = new BufferedWriter(fileDotsResults);
            try {
                outPutDotsResults.write("ImageName\t#Nucleus\tNucleus Volume\t#PML dot\tPML dot IntDensity\tPML dot Volume\tPML dot center-center distance\n");
                outPutDotsResults.flush();
            } catch (IOException ex) {
                Logger.getLogger(PML_FixedCells.class.getName()).log(Level.SEVERE, null, ex);
            }
            
            for (String f : imageFiles) {
                reader.setId(f);
                String rootName = FilenameUtils.getBaseName(f);
                ImporterOptions options = new ImporterOptions();
                options.setId(f);
                options.setSplitChannels(true);
                options.setQuiet(true);
                options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                if (reader.getSizeZ() - 2*tools.zSlicesToIgnore > 2) {
                    options.setZBegin(0, tools.zSlicesToIgnore);
                    options.setZEnd(0, reader.getSizeZ()-tools.zSlicesToIgnore-1);
                }
                
                // open DAPI Channel
                
                System.out.println("--- Opening nuclei channel  ...");
                int indexCh = ArrayUtils.indexOf(chsName,channels[0]);
                ImagePlus imgDAPI = BF.openImagePlus(options)[indexCh];
                // Find nucleus with stardist
                Objects3DIntPopulation dapiPop = tools.stardistNucleiPop(imgDAPI, tools.stardistCellModel);
                int totalDapi = dapiPop.getNbObjects();
                System.out.println(totalDapi +" nucleus found");
                // save nucleus populations 
                tools.saveNucleus(dapiPop, imgDAPI, outDirResults+rootName+"_nucleiObjects.tif");
                tools.flush_close(imgDAPI);
                        
                // Open Orginal PML channel to read dot intensity
                
                System.out.println("--- Opening PML channel  ...");
                indexCh = ArrayUtils.indexOf(chsName,channels[1]);
                ImagePlus imgPML = BF.openImagePlus(options)[indexCh];
                // For all nucleus crop image
                // Find PML in nucleus                        
                for (Object3DInt nucObj : dapiPop.getObjects3DInt()) {
                    BoundingBox box = nucObj.getBoundingBox();
                    int ZStartNuc = box.zmin +1;
                    int ZStopNuc = box.zmax + 1;
                    Roi roiBox = new Roi(box.xmin, box.ymin, box.xmax-box.xmin + 1 , box.ymax - box.ymin + 1);
                    imgPML.setRoi(roiBox);
                    imgPML.updateAndDraw();
                    // Crop image
                    ImagePlus imgNucPML = new Duplicator().run(imgPML, ZStartNuc, ZStopNuc);
                    imgNucPML.deleteRoi();
                    imgNucPML.updateAndDraw();
                    nucObj.translate(-box.xmin, -box.ymin, -ZStartNuc + 1);

                    // Detect PML in nucleus
                    Objects3DIntPopulation pmlPop = new Objects3DIntPopulation();
                    if (tools.dotsDetection.equals("Stardist")) {
                        pmlPop = tools.stardistDotsPopInCells(imgNucPML);
                    }
                    else {
                        pmlPop = tools.findDots(imgNucPML);
                    }
                    System.out.println("Nucleus "+nucObj.getLabel()+" PML = "+pmlPop.getNbObjects());
                    
                    // pre-processing PML diffus image intensity 
                    ImagePlus imgDotsDup = imgNucPML.duplicate();
                    tools.flush_close(imgNucPML);
                    tools.pmlDiffus(pmlPop, nucObj, imgDotsDup, false);
                    // intensity filter
                    Objects3DIntPopulation pmlPopIntFilter = tools.ObjectsIntFilter(nucObj, pmlPop, imgDotsDup);
                    System.out.println("Nucleus "+nucObj.getLabel()+" PML after intensity filter = "+pmlPopIntFilter.getNbObjects());
                    
                   // Find PML diffus intensity on pml filtered intensity
                    tools.pmlDiffus(pmlPopIntFilter, nucObj, imgDotsDup, true);
                    // save diffuse image
                    tools.saveDiffusImage(pmlPopIntFilter, nucObj, imgDotsDup, outDirResults, rootName) ;
                    // Compute parameters                        
                    // nucleus volume, nb of PML, mean PML intensity, mean PLM volume
                    IJ.showStatus("Writing parameters ...");
                    tools.computeNucParameters2(nucObj, pmlPopIntFilter, imgDotsDup, rootName, outDirResults, outPutResults);
                    tools.computeNucParameters(nucObj, pmlPopIntFilter, imgDotsDup, rootName, outPutDotsResults);
                    
                    // Save objects image
                    ImageHandler imhDotsObjects = ImageHandler.wrap(imgDotsDup).createSameDimensions();
                    ImageHandler imhNucObjects = imhDotsObjects.duplicate();
                    pmlPopIntFilter.drawInImage(imhDotsObjects);
                    nucObj.drawObject(imhNucObjects, 255);
                    tools.labelsObject(nucObj, imhNucObjects);
                    ImagePlus[] imgColors = {null,imhDotsObjects.getImagePlus(), imhNucObjects.getImagePlus()};
                    ImagePlus imgObjects = new RGBStackMerge().mergeHyperstacks(imgColors, false);
                    imgObjects.setCalibration(tools.cal);
                    IJ.run(imgObjects, "Enhance Contrast", "saturated=0.35");
                    FileSaver ImgObjectsFile = new FileSaver(imgObjects);
                    ImgObjectsFile.saveAsTiff(outDirResults + rootName + "_"  + "Nuc" + nucObj.getLabel() + "-PML_Objects.tif");
                    tools.flush_close(imgObjects);
                    tools.flush_close(imhDotsObjects.getImagePlus());
                    tools.flush_close(imhNucObjects.getImagePlus());
                    tools.flush_close(imgDotsDup);
                }
                tools.flush_close(imgPML);       
            }
        } catch (IOException | FormatException | DependencyException | ServiceException | io.scif.DependencyException ex) {
            Logger.getLogger(PML_FixedCells.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("Process done");
    }    
}    
