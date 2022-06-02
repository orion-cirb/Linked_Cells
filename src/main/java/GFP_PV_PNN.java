/*
 * Find PNN cells (PNN) and PV coloc
 * compute nuclear foci in PNN/PV cells
 * Author Philippe Mailly
 */

import GFP_PV_PNN_Tools.Cells_PV;
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
    public BufferedWriter global_results_analyze;
    public BufferedWriter cells_results_analyze;
   
    
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
            // Global 
            String header= "Image Name\tPNN cells\tPV cells\tPNN/PV cells\tFoci in PNN/PV\tFoci intensity\tFoci volume\tFoci Dapi in PNN/PV\t"
                    + "Foci Dapi intensity\tFoci Dapi volume\n";
            FileWriter fwCellsGlobal = new FileWriter(outDirResults + "GFP_PV_PNN-"+tools.dotsDetection+"Global_Results.xls", false);
            global_results_analyze = new BufferedWriter(fwCellsGlobal);
            global_results_analyze.write(header);
            global_results_analyze.flush();
            
            // Cells
            header= "Image Name\t# PV Cell\tPV/PNN\tPV Vol\tPV Int\tPNN Vol\tPNN Int\tnb Foci GFP\tVol Foci GFP\tInt Foci GFP\tnb Foci DAPI\tVol Foci DAPI\tInt Foci DAPI\n";
            FileWriter fwCells = new FileWriter(outDirResults + "GFP_PV_PNN-"+tools.dotsDetection+"Cells_Results.xls", false);
            cells_results_analyze = new BufferedWriter(fwCells);
            cells_results_analyze.write(header);
            cells_results_analyze.flush();
            
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
                
                // write cells parameters
                ArrayList<Cells_PV> pvCellsList = new ArrayList();
                tools.pvCellsParameters (pvPop, pvCellsList, imgPV);
                
                // open PNN Channel
                // Find points in xml file
                ArrayList<Point3DInt> PNNPoints = tools.readXML(xmlFile);
                System.out.println("--- Opening PNN channel  ...");
                indexCh = ArrayUtils.indexOf(chsName,channels[1]);
                ImagePlus imgPNN = BF.openImagePlus(options)[indexCh];
                // Find PNN cells with xml positions
                Objects3DIntPopulation pnnPop = tools.findPNNCellsRidge(imgPNN, PNNPoints);
                int pnnCells = pnnPop.getNbObjects();
                System.out.println(pnnCells +" PNN cells found");
                
                // Find PV coloc with PNN cells
                Objects3DIntPopulation pv_pnnCellsPop = tools.findColocCells(pvPop, pnnPop, pvCellsList, imgPNN);
                int pv_pnnCells = pv_pnnCellsPop.getNbObjects();
                System.out.println(pv_pnnCells +" PV colocalized with PNN cells");
                tools.flush_close(imgPNN);
                
                // Finding dots in Foci GFP channel
                System.out.println("--- Opening GFP channel  ...");
                indexCh = ArrayUtils.indexOf(chsName,channels[2]);
                ImagePlus imgGfpFoci = BF.openImagePlus(options)[indexCh];
                Objects3DIntPopulation fociGfpPop = new Objects3DIntPopulation();
                if (tools.dotsDetection.equals("stardist"))
                    fociGfpPop = tools.stardistNucleiPop(imgGfpFoci, false, tools.stardistDotModel, tools.minFociGfpVol, tools.maxFociGfpVol);
                else
                    fociGfpPop = tools.findDots(imgGfpFoci, "gfp");
                System.out.println(fociGfpPop.getNbObjects()+" total foci GFP found");
                
                // Finding foci GFP in PV cells
                Objects3DIntPopulation pvFociGfpPop = new Objects3DIntPopulation();
                int pvFociGfp = 0;
                double pvFociGfpInt = 0;
                double pvFociGfpVol = 0;
                if (pvCells > 0) {
                    pvFociGfpPop = tools.findDotsinCells(pvPop, fociGfpPop, pvCellsList, imgGfpFoci, "GFP");
                    pvFociGfp = pvFociGfpPop.getNbObjects();
                    // Find total dot intensity and volume
                    pvFociGfpInt = tools.findPopIntensity(pvFociGfpPop, imgGfpFoci);
                    pvFociGfpVol = tools.findPopVolume(pvFociGfpPop);
                }
                System.out.println(pvFociGfp +" foci GFP in PV cells found");
                tools.flush_close(imgGfpFoci);

                // Finding dots in Foci DAPI channel
                System.out.println("--- Opening DAPI channel  ...");
                indexCh = ArrayUtils.indexOf(chsName,channels[3]);
                ImagePlus imgDapiFoci = BF.openImagePlus(options)[indexCh];
                Objects3DIntPopulation fociDapiPop = new Objects3DIntPopulation();
                if (tools.dotsDetection.equals("stardist"))
                    fociDapiPop = tools.stardistNucleiPop(imgDapiFoci, false, tools.stardistDotModel, tools.minFociDapiVol, tools.maxFociDapiVol);
                else
                    fociDapiPop = tools.findDots(imgDapiFoci, "dapi");
                System.out.println(fociDapiPop.getNbObjects()+" total foci DAPI found");
                
                
                // Finding foci DAPI in PNN/PV cells
                Objects3DIntPopulation pvFociDapiPop = new Objects3DIntPopulation();
                int pvFociDapi = 0;
                double pvFociDapiInt = 0;
                double pvFociDapiVol = 0;
                if (pv_pnnCells > 0) {
                    pvFociDapiPop = tools.findDotsinCells(pvPop, fociDapiPop, pvCellsList, imgDapiFoci, "DAPI");
                    pvFociDapi = pvFociDapiPop.getNbObjects();
                    // Find total dot intensity and volume
                    pvFociDapiInt = tools.findPopIntensity(pvFociDapiPop, imgDapiFoci);
                    pvFociDapiVol = tools.findPopVolume(pvFociDapiPop);
                }
                System.out.println(pvFociGfp +" foci DAPI in PV cells found");
                tools.flush_close(imgDapiFoci);
                
                // Save image objects
                tools.saveImgObjects(pvPop, pnnPop, fociGfpPop, fociDapiPop, pv_pnnCellsPop, rootName+"-"+tools.dotsDetection, imgPV, outDirResults);
                tools.flush_close(imgPV);

                // write global data
                global_results_analyze.write(rootName+"\t"+pnnCells+"\t"+pvCells+"\t"+pv_pnnCells+"\t"+pvFociGfp+"\t"+pvFociGfpInt+"\t"+pvFociGfpVol+
                        "\t"+pvFociDapi+"\t"+pvFociDapiInt+"\t"+pvFociDapiVol+"\n");
                global_results_analyze.flush();
                
                // write cells data
                int index = 0;
                for (Cells_PV pvCell : pvCellsList) {
                    if (index == 0)
                        cells_results_analyze.write(rootName+"\t"+index+"\t"+pvCell.getcellPV_PNN()+"\t"+pvCell.getCellVol()+"\t"+pvCell.getCellInt()+"\t"+
                                pvCell.getcellPNNVol()+"\t"+pvCell.getcellPNNInt()+"\t"+pvCell.getnbFoci()+"\t"+pvCell.getfociVol()+"\t"+pvCell.getfociInt()+"\t"+
                                pvCell.getnbDapiFoci()+"\t"+pvCell.getfociDapiVol()+"\t"+pvCell.getfociDapiInt()+"\n");
                    else
                        cells_results_analyze.write("\t"+index+"\t"+pvCell.getcellPV_PNN()+"\t"+pvCell.getCellVol()+"\t"+pvCell.getCellInt()+"\t"+
                                pvCell.getcellPNNVol()+"\t"+pvCell.getcellPNNInt()+"\t"+pvCell.getnbFoci()+"\t"+pvCell.getfociVol()+"\t"+pvCell.getfociInt()+"\t"+
                                pvCell.getnbDapiFoci()+"\t"+pvCell.getfociDapiVol()+"\t"+pvCell.getfociDapiInt()+"\n");
                    index++;
                    cells_results_analyze.flush();
                }
                
            }

        } catch (IOException | DependencyException | ServiceException | FormatException | io.scif.DependencyException | ParserConfigurationException | SAXException ex) {
            Logger.getLogger(GFP_PV_PNN.class.getName()).log(Level.SEVERE, null, ex);
        }
        IJ.showStatus("Process done");
    }    
}    
