/*
 * Find linked cells between channel 1 and channel 2
 * Count nulber of linked cells versus cells1 and cells2
 * Author Philippe Mailly
 */

import Linked_Cells_Tools.Tools;
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



public class Linked_Cells implements PlugIn {
    
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
            outDirResults = imageDir + File.separator+ "Results"+ File.separator;
            File outDir = new File(outDirResults);
            if (!Files.exists(Paths.get(outDirResults))) {
                outDir.mkdir();
            }
            
            // Write headers results for results file
            FileWriter fileResults = null;
            String resultsName = "results.xls";
            try {
                fileResults = new FileWriter(outDirResults + resultsName, false);
            } catch (IOException ex) {
                Logger.getLogger(Linked_Cells.class.getName()).log(Level.SEVERE, null, ex);
            }
            outPutResults = new BufferedWriter(fileResults);
            try {
                outPutResults.write("ImageName\t#Cells1\t#Cells2\t#Cells1/Cells2\n");
                outPutResults.flush();
            } catch (IOException ex) {
                Logger.getLogger(Linked_Cells.class.getName()).log(Level.SEVERE, null, ex);
            }
            
            
            for (String f : imageFiles) {
                reader.setId(f);
                String rootName = FilenameUtils.getBaseName(f);
                ImporterOptions options = new ImporterOptions();
                options.setId(f);
                options.setSplitChannels(true);
                options.setQuiet(true);
                options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                
                // open Cells1 Channel
                
                System.out.println("--- Opening cell1 channel  ...");
                int indexCh = ArrayUtils.indexOf(chsName,channels[0]);
                ImagePlus imgCells1 = BF.openImagePlus(options)[indexCh];
                // Find cells1 with stardist
                Objects3DIntPopulation cells1Pop = tools.stardistCellsPop(imgCells1, tools.stardistCellModel);
                int totalCells1 = cells1Pop.getNbObjects();
                System.out.println(totalCells1 +" cells1 found");
                
                // open Cells2 Channel
                
                System.out.println("--- Opening cell2 channel  ...");
                indexCh = ArrayUtils.indexOf(chsName,channels[1]);
                ImagePlus imgCells2 = BF.openImagePlus(options)[indexCh];
                // Find cells2 with stardist
                Objects3DIntPopulation cells2Pop = tools.stardistCellsPop(imgCells2, tools.stardistCellModel);
                int totalCells2= cells2Pop.getNbObjects();
                System.out.println(totalCells2 +" cells2 found");
                tools.flush_close(imgCells2);
                        
                // Find cells colocalization
                Objects3DIntPopulation cellsColocPop = tools.findColoc(cells1Pop, cells2Pop);
                int cellsColoc = cellsColocPop.getNbObjects();
                System.out.println(cellsColoc +" colocalized cells found");
                
                // Save objects image
                ImageHandler imhCells1Objects = ImageHandler.wrap(imgCells1).createSameDimensions();
                ImageHandler imhCells2Objects = imhCells1Objects.duplicate();
                ImageHandler imhCellsColocObjects = imhCells1Objects.duplicate();
                cells1Pop.drawInImage(imhCells1Objects);
                cells2Pop.drawInImage(imhCells2Objects);
                cellsColocPop.drawInImage(imhCellsColocObjects);
                
                ImagePlus[] imgColors = {imhCells1Objects.getImagePlus(), imhCells2Objects.getImagePlus(),null,imhCellsColocObjects.getImagePlus()};
                ImagePlus imgObjects = new RGBStackMerge().mergeHyperstacks(imgColors, false);
                imgObjects.setCalibration(tools.cal);
                IJ.run(imgObjects, "Enhance Contrast", "saturated=0.35");
                FileSaver ImgObjectsFile = new FileSaver(imgObjects);
                ImgObjectsFile.saveAsTiff(outDirResults + rootName + "-Objects.tif");
                tools.flush_close(imgObjects);
                tools.flush_close(imhCells1Objects.getImagePlus());
                tools.flush_close(imhCells2Objects.getImagePlus());
                tools.flush_close(imhCellsColocObjects.getImagePlus());
                tools.flush_close(imgCells1);
                
                // Write results
                IJ.showStatus("Writing results ...");
                outPutResults.write(rootName+"\t"+totalCells1+"\t"+totalCells2+"\t"+cellsColoc+"\n");
                outPutResults.flush();
            }
        } catch (IOException | FormatException | DependencyException | ServiceException | io.scif.DependencyException ex) {
            Logger.getLogger(Linked_Cells.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("Process done");
    }    
}    
