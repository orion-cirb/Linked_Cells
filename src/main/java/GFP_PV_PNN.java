/*
 * Find PNN cells (PNN) and PV coloc
 * compute nuclear foci in PNN/PV cells
 * Author Philippe Mailly
 */

import GFP_PV_PNN_Tools.Tools;
import ij.*;
import ij.plugin.PlugIn;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.ParserConfigurationException;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.plugins.BF;
import loci.plugins.util.ImageProcessorReader;
import loci.plugins.in.ImporterOptions;
import mcib3d.geom.Point3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import org.apache.commons.io.FilenameUtils;
import org.scijava.util.ArrayUtils;
import org.xml.sax.SAXException;



public class GFP_PV_PNN implements PlugIn {
    
    Tools tools = new Tools();
    
    private String imageDir = "";
    public String outDirResults = "";
    private boolean canceled = false;
    public String file_ext = "czi";
    public BufferedWriter results_analyze;
   
    
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
            // Find images with nd extension
            file_ext = tools.findImageType(new File(imageDir));
            ArrayList<String> imageFiles = tools.findImages(imageDir, file_ext);
            if (imageFiles == null) {
                IJ.showMessage("Error", "No images found with "+file_ext+" extension");
                return;
            }
            // create output folder
            outDirResults = imageDir + File.separator+ "Results"+ File.separator;
            File outDir = new File(outDirResults);
            if (!Files.exists(Paths.get(outDirResults))) {
                outDir.mkdir();
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
            // Write header
            String header= "Image Name\tPNN cells\tPV cells\tPNN/PV cells\tFoci in PNN/PV\tFoci intensity\tFoci volume\n";
            FileWriter fwNucleusGlobal = new FileWriter(outDirResults + "GFP_PV_PNN-"+tools.dotsDetection+"Results.xls", false);
            results_analyze = new BufferedWriter(fwNucleusGlobal);
            results_analyze.write(header);
            results_analyze.flush();
            
            for (String f : imageFiles) {
                reader.setId(f);
                String rootName = FilenameUtils.getBaseName(f);
                // Find xml points file
                String xmlFile = imageDir + rootName + ".xml";
                if (!new File(xmlFile).exists()) {
                    IJ.showStatus("No XML file found !") ;
                    break;
                }
                ImporterOptions options = new ImporterOptions();
                options.setId(f);
                options.setSplitChannels(true);
                options.setQuiet(true);
                options.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE);
                
                // open PV Channel
                System.out.println("--- Opening PV channel  ...");
                int indexCh = ArrayUtils.indexOf(chsName,channels[0]);
                ImagePlus imgPV = BF.openImagePlus(options)[indexCh];
                // Find PV cells with stardist
                Objects3DIntPopulation pvPop = tools.stardistNucleiPop(imgPV, true, tools.stardistCellModel, tools.minCellVol, tools.maxCellVol);
                int pvCells = pvPop.getNbObjects();
                System.out.println(pvCells +" PV cells found");
                
                // open PNN Channel
                // Find points in xml file
                ArrayList<Point3DInt> PNNPoints = tools.readXML(xmlFile);
                System.out.println("--- Opening PNN channel  ...");
                indexCh = ArrayUtils.indexOf(chsName,channels[1]);
                ImagePlus imgPNN = BF.openImagePlus(options)[indexCh];
                // Find PNN cells with xml positions
                Objects3DIntPopulation wfaPop = tools.findPNNCellsRidge(imgPNN, PNNPoints);
                int wfaCells = wfaPop.getNbObjects();
                System.out.println(wfaCells +" PNN cells found");
                tools.flush_close(imgPNN);
                
                // Find PV coloc with PNN cells
                Objects3DIntPopulation pv_wfaCellsPop = tools.findColocCells(pvPop, wfaPop);
                int pv_wfaCells = pv_wfaCellsPop.getNbObjects();
                System.out.println(pv_wfaCells +" PV colocalized with PNN cells");
                
                // Finding dots
                System.out.println("--- Opening GFP channel  ...");
                indexCh = ArrayUtils.indexOf(chsName,channels[2]);
                ImagePlus imgDots = BF.openImagePlus(options)[indexCh];
                Objects3DIntPopulation dotsPop = new Objects3DIntPopulation();
                if (tools.dotsDetection.equals("stardist"))
                    dotsPop = tools.stardistNucleiPop(imgDots, false, tools.stardistDotModel, tools.minDotVol, tools.maxDotVol);
                else
                    dotsPop = tools.findDots(imgDots);
                System.out.println(dotsPop.getNbObjects()+" total dots found");
                
                // Finding dots in PNN/PV cells
                Objects3DIntPopulation pvDotsPop = new Objects3DIntPopulation();
                int pvDots = 0;
                double dotsInt = 0;
                double dotsVol = 0;
                if (pv_wfaCells > 0) {
                    pvDotsPop = tools.findDotsinCells(pv_wfaCellsPop, dotsPop);
                    pvDots = pvDotsPop.getNbObjects();
                    // Find total dot intensity and volume
                    dotsInt = tools.findPopIntensity(pvDotsPop, imgDots);
                    dotsVol = tools.findPopVolume(pvDotsPop);
                }
                System.out.println(pvDots +" dots in PV cells found");
                tools.flush_close(imgDots);
                
                // Save image objects
                tools.saveImgObjects(wfaPop, pvPop, dotsPop, pv_wfaCellsPop, rootName+"-"+tools.dotsDetection, imgPV, outDirResults);
                tools.flush_close(imgPV);


                // write data
                results_analyze.write(rootName+"\t"+wfaCells+"\t"+pvCells+"\t"+pv_wfaCells+"\t"+pvDots+"\t"+dotsInt+"\t"+dotsVol+"\n");
                results_analyze.flush();
            }

        } catch (IOException | DependencyException | ServiceException | FormatException | io.scif.DependencyException | ParserConfigurationException | SAXException ex) {
            Logger.getLogger(GFP_PV_PNN.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("Process done");
    }    
}    
