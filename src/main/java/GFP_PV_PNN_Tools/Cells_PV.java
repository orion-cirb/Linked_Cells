/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package GFP_PV_PNN_Tools;

import mcib3d.geom.Voxel3D;

/**
 *
 * @author phm
 */
public class Cells_PV {
    private double cellVol;
    private double cellInt;
    private boolean cellPV_PNN;
    private double cellPNNInt;
    private double cellPNNVol;
    private int nbFoci;
    private double fociVol;
    private double fociInt;
    private int nbDapiFoci;
    private double fociDapiVol;
    private double fociDapiInt;
    
   
	
	public Cells_PV(double cellVol, double cellInt, boolean cellPV_PNN, double cellPNNInt, double cellPNNVol, int nbFoci, double fociVol, double fociInt,
            int nbDapiFoci, double fociDapiVol, double fociDapiInt) {
            this.cellVol = cellVol;
            this.cellInt = cellInt;
            this.cellPV_PNN = cellPV_PNN;
            this.cellPNNInt = cellPNNInt;
            this.cellPNNVol = cellPNNVol;
            this.nbFoci = nbFoci;
            this.fociVol = fociVol;
            this.fociInt = fociInt;
            this.nbDapiFoci = nbDapiFoci;
            this.fociDapiVol = fociDapiVol;
            this.fociDapiInt = fociDapiInt;
	}
        
        public void setCellVol(double cellVol) {
            this.cellVol = cellVol;
	}
        
        public void setCellInt(double cellInt) {
            this.cellInt = cellInt;
	}
        
        public void setcellPV_PNN(boolean cellPV_PNN) {
            this.cellPV_PNN = cellPV_PNN;
	}
        
        public void setcellPNNVol(double cellPNNVol) {
            this.cellPNNVol = cellPNNVol;
	}
        
        public void setcellPNNInt(double cellPNNInt) {
            this.cellPNNInt = cellPNNInt;
	}
        
        public void setnbFoci(int nbFoci) {
            this.nbFoci = nbFoci;
	}
        
        public void setfociVol(double fociVol) {
            this.fociVol = fociVol;
	}
        
        public void setfociInt(double fociInt) {
            this.fociInt = fociInt;
	}
        
        public void setnbDapiFoci(int nbDapiFoci) {
            this.nbDapiFoci = nbDapiFoci;
	}
        
        public void setfociDapiVol(double fociDapiVol) {
            this.fociDapiVol = fociDapiVol;
	}
        
        public void setfociDapiInt(double fociDapiInt) {
            this.fociDapiInt = fociDapiInt;
	}
        
        public double getCellVol() {
            return(cellVol);
	}
        
        public double getCellInt() {
            return(cellInt);
	}
        
        public boolean getcellPV_PNN() {
            return(cellPV_PNN);
	}
        
        public double getcellPNNVol() {
            return(cellPNNVol);
	}
        
        public double getcellPNNInt() {
            return(cellPNNInt);
	}
        
        public int getnbFoci() {
            return(nbFoci);
	}
        
        public double getfociVol() {
            return(fociVol);
	}
        
        public double getfociInt() {
            return(fociInt);
	}
        
        public int getnbDapiFoci() {
            return(nbDapiFoci);
	}
        
        public double getfociDapiVol() {
            return(fociDapiVol);
	}
        
        public double getfociDapiInt() {
            return(fociDapiInt);
	}
        
        
}
