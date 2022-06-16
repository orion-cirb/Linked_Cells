package GFP_PV_PNN_Tools;


import GFP_PV_PNNStardistOrion.StarDist2D;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.FileSaver;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.RGBStackMerge;
import ij.process.ImageProcessor;
import io.scif.DependencyException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.ImageIcon;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import loci.common.services.ServiceException;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.plugins.util.ImageProcessorReader;
import mcib3d.geom.Object3D;
import mcib3d.geom.Objects3DPopulation;
import mcib3d.geom.Point3D;
import mcib3d.geom.Point3DInt;
import mcib3d.geom.Voxel3D;
import mcib3d.geom2.BoundingBox;
import mcib3d.geom2.Object3DInt;
import mcib3d.geom2.Objects3DIntPopulation;
import mcib3d.geom2.VoxelInt;
import mcib3d.geom2.measurements.MeasureCentroid;
import mcib3d.geom2.measurements.MeasureIntensity;
import mcib3d.geom2.measurements.MeasureVolume;
import mcib3d.geom2.measurementsPopulation.MeasurePopulationColocalisation;
import mcib3d.image3d.ImageHandler;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

/**
 *
 * @author phm
 */
public class Tools {

    public boolean canceled = false;
    public double minCellVol = 300;
    public double maxCellVol = 1500;
    public double minFociGfpVol = 0.2;
    public double maxFociGfpVol = 20;
    public double minFociDapiVol = 0.2;
    public double maxFociDapiVol = 20;
    
    private double minDist = 20;
    
    private Object syncObject = new Object();
    private final double stardistPercentileBottom = 0.2;
    private final double stardistPercentileTop = 99.8;
    private final double stardistProbThreshNuc = 0.75;
    private final double stardistOverlayThreshNuc = 0.25;
    private final double stardistProbThreshDot = 0.9;
    private final double stardistOverlayThreshDot = 0.25;
    private File modelsPath = new File(IJ.getDirectory("imagej")+File.separator+"models");
    private String stardistOutput = "Label Image"; 
    public String stardistCellModel = "";
    public String stardistDotModel = "";
    
    public String[] dotsDetections = {"Stardist", "DOG"};
    public String dotsDetection = "";
    double minDotDOG = 1;
    double maxDotDOG = 2;
    
    public String[] channelNames = {"PV", "PNN", "GFP", "DAPI"};
    public Calibration cal;
    
    public int zSlicesToIgnore = 2;
    
    private final ImageIcon icon = new ImageIcon(this.getClass().getResource("/Orion_icon.png"));

    private CLIJ2 clij2 = CLIJ2.getInstance();
    
    
     /**
     * check  installed modules
     * @return 
     */
    public boolean checkInstalledModules() {
        // check install
        ClassLoader loader = IJ.getClassLoader();
        try {
            loader.loadClass("net.haesleinhuepf.clij2.CLIJ2");
        } catch (ClassNotFoundException e) {
            IJ.log("CLIJ not installed, please install from update site");
            return false;
        }
        try {
            loader.loadClass("mcib3d.geom.Object3D");
        } catch (ClassNotFoundException e) {
            IJ.log("3D ImageJ Suite not installed, please install from update site");
            return false;
        }
        return true;
    }
    
     /*
    Find starDist models in Fiji models folder
    */
    public String[] findStardistModels() {
        FilenameFilter filter = (dir, name) -> name.endsWith(".zip");
        File[] modelList = modelsPath.listFiles(filter);
        String[] models = new String[modelList.length];
        for (int i = 0; i < modelList.length; i++) {
            models[i] = modelList[i].getName();
        }
        Arrays.sort(models);
        return(models);
    }   
    
    /* Median filter 
     * Using CLIJ2
     * @param ClearCLBuffer
     * @param sizeXY
     * @param sizeZ
     */ 
    public ClearCLBuffer median_filter(ClearCLBuffer  imgCL, double sizeXY, double sizeZ) {
        ClearCLBuffer imgCLMed = clij2.create(imgCL);
        clij2.mean3DBox(imgCL, imgCLMed, sizeXY, sizeXY, sizeZ);
        clij2.release(imgCL);
        return(imgCLMed);
    }
    
    /**
     * Reset labels of the objects
     */
    public void resetLabels(Objects3DIntPopulation pop){
        float label = 0;
        for(Object3DInt obj: pop.getObjects3DInt()) {
            obj.setLabel(label);
            label++;
        }
    }

    /**
     * Remove object with one Z
     */
    public Objects3DIntPopulation zFilterPop (Objects3DIntPopulation pop) {
        Objects3DIntPopulation popZ = new Objects3DIntPopulation();
        for (Object3DInt obj : pop.getObjects3DInt()) {
            int zmin = obj.getBoundingBox().zmin;
            int zmax = obj.getBoundingBox().zmax;
            if (zmax != zmin)
                popZ.addObject(obj);
        }
        resetLabels(popZ);
        return popZ;
    }
    
     /**
     * return objects population in an ClearBuffer image
     * @param imgCL
     * @return pop objects population
     */

    public Objects3DIntPopulation getPopFromClearBuffer(ClearCLBuffer imgBin, double min, double max) {
        ClearCLBuffer labelsCL = clij2.create(imgBin);
        clij2.connectedComponentsLabelingBox(imgBin, labelsCL);
        ClearCLBuffer labelsSizeFilter = clij2.create(imgBin);
        // filter size
        clij2.excludeLabelsOutsideSizeRange(labelsCL, labelsSizeFilter, min/(cal.pixelWidth*cal.pixelWidth*cal.pixelDepth),
                max/(cal.pixelWidth*cal.pixelWidth*cal.pixelDepth));
        clij2.release(labelsCL);
        ImagePlus img = clij2.pull(labelsSizeFilter);
        clij2.release(labelsSizeFilter);
        ImageHandler imh = ImageHandler.wrap(img);
        flush_close(img);
        Objects3DIntPopulation pop = new Objects3DIntPopulation(imh);
        pop.setVoxelSizeXY(cal.pixelWidth);
        pop.setVoxelSizeZ(cal.pixelDepth);
        imh.closeImagePlus();
        return(pop);
    }  
    
    
    
    /**
    Fill cells parameters
    */
    public void pvCellsParameters (Objects3DIntPopulation cellsPop, ArrayList<Cells_PV> pvCells, ImagePlus imgPv, ImagePlus imgGFP) {
        for (Object3DInt obj : cellsPop.getObjects3DInt()) {
            double pvVol = new MeasureVolume(obj).getVolumeUnit();
            double pvInt = new MeasureIntensity(obj, ImageHandler.wrap(imgPv)).getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
            double pvGFPInt = new MeasureIntensity(obj, ImageHandler.wrap(imgGFP)).getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
            Cells_PV pv = new Cells_PV(pvVol, pvInt, pvGFPInt, false, 0, 0, 0, 0, 0, 0, 0, 0);
            pvCells.add(pv);
        }
        
    }
    
    public String[] dialog(String[] chs) {
        String[] models = findStardistModels();
        GenericDialogPlus gd = new GenericDialogPlus("Parameters");
        gd.setInsets​(0, 100, 0);
        gd.addImage(icon);
        gd.addMessage("Channels", Font.getFont("Monospace"), Color.blue);
        int index = 0;
        for (String chNames : channelNames) {
            gd.addChoice(chNames+" : ", chs, chs[index]);
            index++;
        }
        gd.addMessage("Stack", Font.getFont(Font.MONOSPACED), Color.blue);
        gd.addNumericField("nb Z slices to ignore : ", zSlicesToIgnore);
        gd.addMessage("--- Stardist model ---", Font.getFont(Font.MONOSPACED), Color.blue);
        if (models.length > 0) {
            gd.addChoice("StarDist cell model :",models, models[0]);
            gd.addChoice("StarDist dots model :",models, models[1]);
        }
        else {
            gd.addMessage("No StarDist model found in Fiji !!", Font.getFont("Monospace"), Color.red);
            gd.addFileField("StarDist cell model :", stardistCellModel);
            gd.addFileField("StarDist dots model :", stardistDotModel);
        }
        gd.addMessage("Cells detection", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("min cell volume : ", minCellVol);
        gd.addNumericField("max cell volume : ", maxCellVol);
        gd.addMessage("Dots detection", Font.getFont("Monospace"), Color.blue);
        gd.addChoice("Method : "+" : ", dotsDetections, dotsDetections[0]);
        gd.addNumericField("min DOG : ", minDotDOG);
        gd.addNumericField("max DOG : ", maxDotDOG);
        gd.addMessage("Foci size filter", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("min foci GFP : ", minFociGfpVol);
        gd.addNumericField("max foci GFP : ", maxFociGfpVol);
        gd.addNumericField("min foci DAPI : ", minFociDapiVol);
        gd.addNumericField("max foci DAPI : ", maxFociDapiVol);
         gd.addNumericField("min distance PNN PV Cells : ", minDist);
        gd.addMessage("Image calibration", Font.getFont("Monospace"), Color.blue);
        gd.addNumericField("Pixel size : ", cal.pixelWidth);
        gd.showDialog();
        if (gd.wasCanceled())
            canceled = true;
        String[] chChoices = new String[channelNames.length];
        for (int n = 0; n < chChoices.length; n++) 
            chChoices[n] = gd.getNextChoice();
        zSlicesToIgnore = (int) gd.getNextNumber();
        if (models.length > 0) {
            stardistCellModel = modelsPath+File.separator+gd.getNextChoice();
            stardistDotModel = modelsPath+File.separator+gd.getNextChoice();
        }
        else {
            stardistCellModel = gd.getNextString();
            stardistDotModel = gd.getNextString();
        }
        if (stardistCellModel.isEmpty() || stardistDotModel.isEmpty()) {
            IJ.error("No model specify !!");
            return(null);
        }
        minCellVol = gd.getNextNumber();
        maxCellVol = gd.getNextNumber();
        dotsDetection = gd.getNextChoice();
        minDotDOG = gd.getNextNumber();
        maxDotDOG = gd.getNextNumber();
        minFociGfpVol = gd.getNextNumber();
        maxFociGfpVol = gd.getNextNumber();
        minFociDapiVol = gd.getNextNumber();
        maxFociDapiVol = gd.getNextNumber();
        minDist = gd.getNextNumber();
        cal.pixelWidth = cal.pixelHeight = gd.getNextNumber();
        return(chChoices);
    }
     
    // Flush and close images
    public void flush_close(ImagePlus img) {
        img.flush();
        img.close();
    }

    
/**
     * Find images in folder
     * @param imagesFolder
     * @param imageExt
     * @return 
     */
    public ArrayList<String> findImages(String imagesFolder, String imageExt) {
        File inDir = new File(imagesFolder);
        String[] files = inDir.list();
        if (files == null) {
            System.out.println("No Image found in "+imagesFolder);
            return null;
        }
        ArrayList<String> images = new ArrayList();
        for (String f : files) {
            // Find images with extension
            String fileExt = FilenameUtils.getExtension(f);
            if (fileExt.equals(imageExt))
                images.add(imagesFolder + File.separator + f);
        }
        Collections.sort(images);
        return(images);
    }
    
     /**
     * Find image type
     */
    public String findImageType(File imagesFolder) {
        String ext = "";
        String[] files = imagesFolder.list();
        for (String name : files) {
            String fileExt = FilenameUtils.getExtension(name);
            switch (fileExt) {
               case "nd" :
                   ext = fileExt;
                   break;
                case "czi" :
                   ext = fileExt;
                   break;
                case "lif"  :
                    ext = fileExt;
                    break;
                case "isc2" :
                    ext = fileExt;
                    break;
            }
        }
        return(ext);
    }
    
     /**
     * Find image calibration
     * @param meta
     * @return 
     */
    public Calibration findImageCalib(IMetadata meta) {
        cal = new Calibration();  
        // read image calibration
        cal.pixelWidth = meta.getPixelsPhysicalSizeX(0).value().doubleValue();
        cal.pixelHeight = cal.pixelWidth;
        if (meta.getPixelsPhysicalSizeZ(0) != null)
            cal.pixelDepth = meta.getPixelsPhysicalSizeZ(0).value().doubleValue();
        else
            cal.pixelDepth = 1;
        cal.setUnit("microns");
        return(cal);
    }
    
    
     /**
     * Find channels name
     * @param imageName
     * @return 
     * @throws loci.common.services.DependencyException
     * @throws loci.common.services.ServiceException
     * @throws loci.formats.FormatException
     * @throws java.io.IOException
     */
    public String[] findChannels (String imageName, IMetadata meta, ImageProcessorReader reader) throws DependencyException, ServiceException, FormatException, IOException {
        int chs = reader.getSizeC();
        String[] channels = new String[chs];
        String imageExt =  FilenameUtils.getExtension(imageName);
        switch (imageExt) {
            case "nd" :
                for (int n = 0; n < chs; n++) 
                {
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n).toString();
                }
                break;
            case "lif" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null || meta.getChannelName(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelName(0, n).toString();
                break;
            case "czi" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelFluor(0, n).toString();
                break;
            case "ics" :
                for (int n = 0; n < chs; n++) 
                    if (meta.getChannelID(0, n) == null)
                        channels[n] = Integer.toString(n);
                    else 
                        channels[n] = meta.getChannelExcitationWavelength(0, n).value().toString();
                break;    
            default :
                for (int n = 0; n < chs; n++)
                    channels[n] = Integer.toString(n);
        }
        return(channels);         
    }
    
     
    /** Look for all nuclei
    Do z slice by slice stardist 
    * return nuclei population
    */
   public Objects3DIntPopulation stardistNucleiPop(ImagePlus imgNuc, String stardistModel, double minVol, double maxVol) throws IOException{
       ImagePlus img = null;
       // resize to be in a stardist-friendly scale
       int width = imgNuc.getWidth();
       int height = imgNuc.getHeight();
       float factor = 0.25f;
       boolean resized = false;
       if (imgNuc.getWidth() > 512) {
           img = imgNuc.resize((int)(width*factor), (int)(height*factor), 1, "none");
           resized = true;
       }
       else
           img = new Duplicator().run(imgNuc);
       
       IJ.run(img, "Remove Outliers", "block_radius_x=10 block_radius_y=10 standard_deviations=1 stack");
       ClearCLBuffer imgCL = clij2.push(img);
       ClearCLBuffer imgCLM = clij2.create(imgCL);
       imgCLM = median_filter(imgCL, 2, 2);
       clij2.release(imgCL);
       ImagePlus imgM = clij2.pull(imgCLM);
       clij2.release(imgCLM);
       flush_close(img);

       // Go StarDist
       File starDistModelFile = new File(stardistModel);
       StarDist2D star = new StarDist2D(syncObject, starDistModelFile);
       star.loadInput(imgM);
       star.setParams(stardistPercentileBottom, stardistPercentileTop, stardistProbThreshNuc, stardistOverlayThreshNuc, stardistOutput);
       star.run();
       flush_close(imgM);
       // label in 3D
       ImagePlus nuclei = (resized) ? star.getLabelImagePlus().resize(width, height, 1, "none") : star.getLabelImagePlus();
       imgCL = clij2.push(nuclei);
       Objects3DIntPopulation nPop = getPopFromClearBuffer(imgCL, minVol, maxVol); 
       clij2.release(imgCL);
       Objects3DIntPopulation popZ = zFilterPop(nPop);  
       flush_close(nuclei);
       return(popZ);
   }
   
    /** Look for all nuclei in given population of cells
    Do z slice by slice stardist 
    * return nuclei population
    */
    public Objects3DIntPopulation stardistDotsPopInCells(ImagePlus imgNuc, String stardistModel, double minVol, double maxVol, Objects3DIntPopulation cellPop) throws IOException{
        File starDistModelFile = new File(stardistModel);
        StarDist2D star = new StarDist2D(syncObject, starDistModelFile);
        star.setParams(stardistPercentileBottom, stardistPercentileTop, stardistProbThreshNuc, stardistOverlayThreshNuc, stardistOutput);
            
        // Go StarDist
        Objects3DIntPopulation dotsPop = new Objects3DIntPopulation();
        float dotLabel = 0;
        for (Object3DInt cell: cellPop.getObjects3DInt()) {
            BoundingBox box = cell.getBoundingBox();
            Roi roi = new Roi(box.xmin, box.ymin, box.xmax-box.xmin, box.ymax-box.ymin);
            imgNuc.setRoi(roi);
            ImagePlus imgCrop = new Duplicator().run(imgNuc, box.zmin+1, box.zmax+1);
            
            ClearCLBuffer imgCL = clij2.push(imgCrop);
            flush_close(imgCrop);
            ClearCLBuffer imgCLM = clij2.create(imgCL);
            imgCLM = median_filter(imgCL, 4, 4);
            clij2.release(imgCL);
            ImagePlus imgM = clij2.pull(imgCLM);
            clij2.release(imgCLM);
        
            star.loadInput(imgM);
            star.run();
            flush_close(imgM);
            
            // label in 3D
            ImagePlus nuclei = star.getLabelImagePlus();
            imgCL = clij2.push(nuclei);
            flush_close(nuclei);
            Objects3DIntPopulation pop = getPopFromClearBuffer(imgCL, minVol, maxVol); 
            clij2.release(imgCL);
            for(Object3DInt dot: pop.getObjects3DInt()) {
                dot.setLabel(dotLabel);
                int tx = box.xmin;
                int ty = box.ymin;
                int tz = box.zmin;
                dot.translate(tx, ty, tz);
                dotsPop.addObject(dot);
                dotLabel++;
            } 
        }
        return(dotsPop);
    }
   
   
   /**
     * Threshold 
     * USING CLIJ2
     * @param imgCL
     * @param thMed
     * @param fill 
     */
    public ClearCLBuffer threshold(ClearCLBuffer imgCL, String thMed) {
        ClearCLBuffer imgCLBin = clij2.create(imgCL);
        clij2.automaticThreshold(imgCL, imgCLBin, thMed);
        return(imgCLBin);
    }
   
   /**
     * Difference of Gaussians 
     * Using CLIJ2
     * @param imgCL
     * @param size1
     * @param size2
     * @return imgGauss
     */ 
    private ClearCLBuffer DOG(ClearCLBuffer imgCL, double size1, double size2) {
        ClearCLBuffer imgCLDOG = clij2.create(imgCL);
        clij2.differenceOfGaussian3D(imgCL, imgCLDOG, size1, size1, size1, size2, size2, size2);
        return(imgCLDOG);
    } 
        
 /**
     * Find dots population
     * @param imgTh
     * @param thPop
     * @return 
     */
     public Objects3DIntPopulation findDots(ImagePlus imgTh, String channel, Objects3DIntPopulation cellPop) {
        IJ.showStatus("Finding dots ....");
        Objects3DIntPopulation dotsPop = new Objects3DIntPopulation();
        
        float dotLabel = 0;
        for (Object3DInt cell: cellPop.getObjects3DInt()) {
            BoundingBox box = cell.getBoundingBox();
            Roi roi = new Roi(box.xmin, box.ymin, box.xmax-box.xmin, box.ymax-box.ymin);
            imgTh.setRoi(roi);
            ImagePlus img = new Duplicator().run(imgTh, box.zmin+1, box.zmax+1);
            ClearCLBuffer imgCL = clij2.push(img);
            flush_close(img);
            ClearCLBuffer imgDOG = DOG(imgCL, minDotDOG, maxDotDOG);
            clij2.release(imgCL);
            ClearCLBuffer imgCLBin = threshold(imgDOG, "Moments");
            clij2.release(imgDOG);

            Objects3DIntPopulation pop = new Objects3DIntPopulation();
            if (channel.equals("gfp"))
                pop = getPopFromClearBuffer(imgCLBin, minFociGfpVol, maxFociGfpVol);
            else
                pop = getPopFromClearBuffer(imgCLBin, minFociDapiVol, maxFociDapiVol);
            clij2.release(imgCLBin);
            for(Object3DInt dot: pop.getObjects3DInt()) {
                dot.setLabel(dotLabel);
                int tx = box.xmin;
                int ty = box.ymin;
                int tz = box.zmin;
                dot.translate(tx, ty, tz);
                dotsPop.addObject(dot);
                dotLabel++;
            }
        }
        
        dotsPop.setVoxelSizeXY(cal.pixelWidth);
        dotsPop.setVoxelSizeZ(cal.pixelDepth);
        return(dotsPop);
     }
     
     /**
     * Find volume of objects  
     * @param dotsPop
     * @return vol
     */
    
    public double findPopVolume (Objects3DIntPopulation dotsPop) {
        IJ.showStatus("Findind object's volume");
        List<Double[]> results = dotsPop.getMeasurementsList(new MeasureVolume().getNamesMeasurement());
        double sum = results.stream().map(arr -> arr[1]).reduce(0.0, Double::sum);
        return(sum);
    }
    
    /**
     * Find intensity of objects  
     * @param dotsPop
     * @return intensity
     */
    
    public double findPopIntensity (Objects3DIntPopulation dotsPop, ImagePlus img) {
        IJ.showStatus("Findind object's intensity");
        ImageHandler imh = ImageHandler.wrap(img);
        double sumInt = 0;
        for(Object3DInt obj : dotsPop.getObjects3DInt()) {
            MeasureIntensity intMes = new MeasureIntensity(obj, imh);
            sumInt +=  intMes.getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
        }
        return(sumInt);
    }
    
    /*
    Check if label object exist in pop
    */
    private boolean checkObjLabel(Objects3DIntPopulation pop , Object3DInt obj) {
        boolean check = false;
        for (Object3DInt objPop : pop.getObjects3DInt()) {
            if (objPop.getLabel() == obj.getLabel()) {
                check = true;
                break;
            }
        }
        return(check);
    }
    
    /**
     * Find coloc cells
     * pop1 PV cells
     * pop2 PNN cells
     * @return PV cells in PNN cells
     */
    public Objects3DIntPopulation findColocCells (Objects3DIntPopulation pop1, Objects3DIntPopulation pop2, ArrayList<Cells_PV> pvCells, ImagePlus imgPNN) {
        Objects3DIntPopulation colocPop = new Objects3DIntPopulation();
        IJ.showStatus("Finding colocalized cells population ...");
        for(Object3DInt obj2: pop2.getObjects3DInt()) {
            Point3D PtObj2 =  new MeasureCentroid(obj2).getCentroidAsPoint();
            for(Object3DInt obj1 : pop1.getObjects3DInt()) {
                Point3D PtObj1 =  new MeasureCentroid(obj1).getCentroidAsPoint();
                double dist = PtObj1.distance(PtObj2, cal.pixelWidth, cal.pixelDepth);
                if ((dist <= minDist) ) {
                    if (!checkObjLabel(colocPop, obj1)) {
                        colocPop.addObject(obj1);
                        Cells_PV pvCell = pvCells.get((int)obj1.getLabel());
                        double pnnInt = new MeasureIntensity(obj2, ImageHandler.wrap(imgPNN)).getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
                        double pnnVol = new MeasureVolume(obj2).getValueMeasurement(MeasureVolume.VOLUME_UNIT);
                        pvCell.setcellPV_PNN(true);
                        pvCell.setcellPNNInt(pnnInt);
                        pvCell.setcellPNNVol(pnnVol);
                        pvCells.set((int)obj1.getLabel(), pvCell);
                    }
                    break;
                }
            }
        }
        colocPop.setVoxelSizeXY(cal.pixelWidth);
        colocPop.setVoxelSizeZ(cal.pixelDepth);
        return(colocPop);    
    }
    
    /**
     * Find dots in cells
     * @param cellsPop pv cells
     * @param dotsPop dots
     * @param pvCells
     * @param img
     * @param channel GFP or DAPI
     * @return 
     */
    public Objects3DIntPopulation findDotsinCells (Objects3DIntPopulation cellsPop, Objects3DIntPopulation dotsPop, ArrayList<Cells_PV> pvCells, ImagePlus img, String channel) {
        Objects3DIntPopulation colocPop = new Objects3DIntPopulation();
        IJ.showStatus("Finding dots in cells population ...");
        for (Object3DInt cellObj : cellsPop.getObjects3DInt()) {
            int cellIndex = (int)cellObj.getLabel();
            IJ.showStatus("Finding "+channel+" foci in PV cell "+cellIndex+"/"+cellsPop.getNbObjects());
            Cells_PV pvCell = pvCells.get(cellIndex);
            int dots = 0;
            double dotsVol = 0;
            double dotsInt = 0;
            for (Object3DInt dotObj : dotsPop.getObjects3DInt()) {
                Point3D pt = new MeasureCentroid(dotObj).getCentroidAsPoint();
                if (cellObj.contains(new VoxelInt(pt.getRoundX(), pt.getRoundY(), pt.getRoundZ(), 0))) {
                    dots++;
                    dotsVol += new MeasureVolume(dotObj).getVolumeUnit();
                    dotsInt += new MeasureIntensity(dotObj, ImageHandler.wrap(img)).getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
                    colocPop.addObject(dotObj);
                }
            }
            switch (channel) {
                case "GFP":
                    pvCell.setnbFoci(dots);
                    pvCell.setfociVol(dotsVol);
                    pvCell.setfociInt(dotsInt);
                    break;
                case "DAPI":
                    pvCell.setnbDapiFoci(dots);
                    pvCell.setfociDapiVol(dotsVol);
                    pvCell.setfociDapiInt(dotsInt);
                    break;
                default :
            }
            pvCells.set(cellIndex, pvCell);
        }
        colocPop.setVoxelSizeXY(cal.pixelWidth);
        colocPop.setVoxelSizeZ(cal.pixelDepth);
        return(colocPop);    
    }
    
    /**
     * Find dots in cells
     * @param pop1 pv cells
     * @param pop2 dots
     * @param pvCells
     * @param img
     * @param channel GFP or DAPI
     * @return 
     */
    public Objects3DIntPopulation colocDotsCells (Objects3DIntPopulation cellsPop, Objects3DIntPopulation dotsPop, ArrayList<Cells_PV> pvCells, ImagePlus img, String channel) {
        Objects3DIntPopulation colocPop = new Objects3DIntPopulation();
        IJ.showStatus("Finding dots in cells population ...");
        MeasurePopulationColocalisation coloc = new MeasurePopulationColocalisation(cellsPop, dotsPop);
        ResultsTable table = coloc.getResultsTableOnlyColoc();
        table.show("coloc");
        for (Object3DInt cellObj : cellsPop.getObjects3DInt()) {
            int cellIndex = (int)cellObj.getLabel();
            IJ.showStatus("Finding "+channel+" foci in PV cell "+cellIndex+"/"+cellsPop.getNbObjects());
            Cells_PV pvCell = pvCells.get(cellIndex);
            int dots = 0;
            double dotsVol = 0;
            double dotsInt = 0;
            for (Object3DInt dotObj : dotsPop.getObjects3DInt()) {
                double colocVal = coloc.getValueObjectsPair(cellObj, dotObj);
                if (colocVal > 0) {
                    dots++;
                    dotsVol += new MeasureVolume(dotObj).getVolumeUnit();
                    dotsInt += new MeasureIntensity(dotObj, ImageHandler.wrap(img)).getValueMeasurement(MeasureIntensity.INTENSITY_SUM);
                    colocPop.addObject(dotObj);
                }
            }
            switch (channel) {
                case "GFP":
                    pvCell.setnbFoci(dots);
                    pvCell.setfociVol(dotsVol);
                    pvCell.setfociInt(dotsInt);
                    break;
                case "DAPI":
                    pvCell.setnbDapiFoci(dots);
                    pvCell.setfociDapiVol(dotsVol);
                    pvCell.setfociDapiInt(dotsInt);
                    break;
                default :
            }
            pvCells.set(cellIndex, pvCell);
        }
        colocPop.setVoxelSizeXY(cal.pixelWidth);
        colocPop.setVoxelSizeZ(cal.pixelDepth);
        return(colocPop);    
    }
    
     /**
     * Label object
     * @param popObj
     * @param img 
     */
    public void labelsObject (Object3DInt obj, ImageHandler imh) {
        int fontSize = Math.round(8f/(float)imh.getCalibration().pixelWidth);
        Font tagFont = new Font("SansSerif", Font.PLAIN, fontSize);
        float label = obj.getLabel();
        BoundingBox box = obj.getBoundingBox();
        int z = box.zmin;
        int x = box.xmin - 2;
        int y = box.ymin - 2;
        imh.getImagePlus().setSlice(z+1);
        ImageProcessor ip = imh.getImagePlus().getProcessor();
        ip.setFont(tagFont);
        ip.setColor(255);
        ip.drawString(Integer.toString((int)label), x, y);
        imh.getImagePlus().updateAndDraw();
    }
   
    /**
     * Save dots Population in image
     * @param pop1 PV cells
     * @param pop2 PNN cells
     * @param pop3 fociGFP
     * @param pop4 fociDAPI
     * @param pop5 PV/PNN
     * @param imageName
     * @param img 
     * @param outDir 
     */
    public void saveImgObjects(Objects3DIntPopulation pop1, Objects3DIntPopulation pop2, Objects3DIntPopulation pop3, Objects3DIntPopulation pop4, 
            Objects3DIntPopulation pop5, String imageName, ImagePlus img, String outDir) {
        //create image objects population
        

        //PV cells green
        ImageHandler imgObj1 = ImageHandler.wrap(img).createSameDimensions();
        if (pop1.getNbObjects() > 0)
            for (Object3DInt obj : pop1.getObjects3DInt())
                obj.drawObject(imgObj1, 255);
        
        //PNN cells red
        ImageHandler imgObj2 = imgObj1.createSameDimensions();
        if (pop2.getNbObjects() > 0)
            for (Object3DInt obj : pop2.getObjects3DInt())
                obj.drawObject(imgObj2, 255);
        
        // Foci GFP cyan
        ImageHandler imgObj3 = imgObj1.createSameDimensions();
        if (pop3.getNbObjects() > 0)
            for (Object3DInt obj : pop3.getObjects3DInt())
                obj.drawObject(imgObj3, 255);        
        
        // Foci DAPI blue
        ImageHandler imgObj4 = imgObj1.createSameDimensions();
        if (pop4.getNbObjects() > 0) 
            for (Object3DInt obj : pop4.getObjects3DInt())
                obj.drawObject(imgObj4, 255);
        
        // PV/PNN yellow
        ImageHandler imgObj5 = imgObj1.createSameDimensions();
        if (pop5.getNbObjects() > 0) {
            for (Object3DInt obj : pop5.getObjects3DInt()) {
                    labelsObject(obj, imgObj5);
                    obj.drawObject(imgObj5, 255);
            }
        }
   
        // save image for objects population
        ImagePlus[] imgColors = {imgObj2.getImagePlus(), imgObj1.getImagePlus(), imgObj4.getImagePlus(), null, imgObj3.getImagePlus(),null,imgObj5.getImagePlus() };
        ImagePlus imgObjects = new RGBStackMerge().mergeHyperstacks(imgColors, false);
        imgObjects.setCalibration(img.getCalibration());
        FileSaver ImgObjectsFile = new FileSaver(imgObjects);
        ImgObjectsFile.saveAsTiff(outDir + imageName + "_Objects.tif"); 
        imgObj1.closeImagePlus();
        imgObj2.closeImagePlus();
        imgObj3.closeImagePlus();
        imgObj4.closeImagePlus();
        imgObj5.closeImagePlus();
        flush_close(imgObjects);
    }
    
    /**
     * PNN Cells segmentation with Ridge
     * @param imgCells
     * @param pts
     * @return 
     */
    public Objects3DIntPopulation findPNNCellsRidge(ImagePlus imgCells, ArrayList<Point3DInt> pts) { 
        // Ridge detection
        GFP_PV_PNN_Ridge.Lines_ ridgeDectection = new GFP_PV_PNN_Ridge.Lines_();
        ridgeDectection.lineWidth = 15;
        ridgeDectection.sigma = 2.8;
        ridgeDectection.lowerThresh = 0;
        ridgeDectection.upperThresh = 2.0;
        ridgeDectection.contrastHigh = 120;
        ridgeDectection.contrastLow = 8;
        Objects3DIntPopulation cellPop = new Objects3DIntPopulation();
        int updown = 4;
        int rectSize = 400;
        for (int i = 0; i < pts.size(); i++) {
            Point3DInt pt = pts.get(i);
            Rectangle cellRoi = new Rectangle(pt.getX() - rectSize/2, pt.getY() - rectSize/2, rectSize, rectSize);
            // find cell contours
            int zStart =  (pt.getZ() - updown < 1) ? 1 : pt.getZ() - updown;
            int zStop = (pt.getZ() + updown > imgCells.getNSlices()) ? imgCells.getNSlices() : pt.getZ() + updown;
            ImageStack imgStackBin = new ImageStack(imgCells.getWidth(), imgCells.getHeight());
            int z = 0;
            for (int n = zStart; n <= zStop; n++) {
                imgCells.setSlice(n);
                imgCells.setRoi(cellRoi);
                imgCells.updateAndDraw();
                ImagePlus img = new Duplicator().run(imgCells, n, n);
                IJ.run(img, "8-bit","");
                IJ.showStatus("Finding PNN cell "+(i+1)+"/"+pts.size());
                ridgeDectection.setup("", img);
                ridgeDectection.run(img.getProcessor());
                ImagePlus imgRidge = ridgeDectection.makeBinary();
                imgStackBin.addSlice("", imgRidge.getProcessor(), z);
                flush_close(img);
                z++;
            }
            ImageHandler imh = ImageHandler.wrap(imgStackBin);
            if (imh.getMax() != 0) {
                Object3DInt cellObj = new Object3DInt(imh);
                imh.closeImagePlus();
                cellObj.setLabel((i+1));
                int tx = pt.getX() - rectSize/2 ;
                int ty = pt.getY() - rectSize/2 ;
                cellObj.translate(tx, ty, 0);
                cellPop.addObject(cellObj);
            }
        }
        cellPop.setVoxelSizeXY(cal.pixelWidth);
        cellPop.setVoxelSizeZ(cal.pixelDepth);
        return(cellPop);
    }
    
/**
     * 
     * @param xmlFile
     * @return
     * @throws ParserConfigurationException
     * @throws SAXException
     * @throws IOException 
     */
    public ArrayList<Point3DInt> readXML(String xmlFile) throws ParserConfigurationException, SAXException, IOException {
        ArrayList<Point3DInt> ptList = new ArrayList<>();
        int x = 0, y = 0 ,z = 0;
        File fXmlFile = new File(xmlFile);
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
	DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
	Document doc = dBuilder.parse(fXmlFile);
        doc.getDocumentElement().normalize();
        NodeList nList = doc.getElementsByTagName("Marker");
        for (int n = 0; n < nList.getLength(); n++) {
            Node nNode = nList.item(n);
            if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                Element eElement = (Element) nNode;
                x = Integer.parseInt(eElement.getElementsByTagName("MarkerX").item(0).getTextContent());
                y = Integer.parseInt(eElement.getElementsByTagName("MarkerY").item(0).getTextContent());
                z = Integer.parseInt(eElement.getElementsByTagName("MarkerZ").item(0).getTextContent())-zSlicesToIgnore;
            }
            Point3DInt pt = new Point3DInt(x, y, z);
            ptList.add(pt);
        }
        return(ptList);
    }
    
}
