/*
 * Copyright (c) 2010-2025. Institut Pasteur.
 *
 * This file is part of Icy.
 * Icy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Icy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Icy. If not, see <https://www.gnu.org/licenses/>.
 */

package plugins.fab.trackmanager.processors;

import fr.icy.Icy;
import fr.icy.extension.plugin.annotation_.IcyPluginName;
import fr.icy.gui.canvas.IcyCanvas;
import fr.icy.gui.dialog.MessageDialog;
import fr.icy.gui.viewer.Viewer;
import fr.icy.gui.viewer.ViewerEvent;
import fr.icy.gui.viewer.ViewerListener;
import fr.icy.model.overlay.Overlay;
import fr.icy.model.sequence.Sequence;
import fr.icy.model.sequence.SequenceEvent;
import fr.icy.model.sequence.SequenceListener;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import plugins.fab.trackmanager.PluginTrackManagerProcessor;
import plugins.fab.trackmanager.TrackSegment;
import plugins.nchenouard.spot.Detection;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;

// FIXME : changer la gestion du painter pour tous les getDisplaySequence avec painter. 

/**
 * Track Processor Flow
 * <p>
 * WARNING: BETA Version
 */
@IcyPluginName("Track Processor Flow")
public class TrackProcessorFlow extends PluginTrackManagerProcessor implements ActionListener, SequenceListener, ViewerListener {
    private final TrackProcessorFlowOverlay overlay = new TrackProcessorFlowOverlay();

    //public TrackProcessorFlow(TrackPool trackPool) {
    public TrackProcessorFlow() {
        //super(trackPool , "Track Flow Processor" );
        setName("Track Flow Processor");

//		setPanelHeight( 260 );
//		panel.setLayout( new GridLayout( 1 , 2  ) );

        final JPanel panelLeft = new JPanel();
        panelLeft.setBorder(new TitledBorder("Options"));
        panelLeft.setLayout(new BoxLayout(panelLeft, BoxLayout.PAGE_AXIS));

        affectSequenceButton.setSelected(true);
        affectSequenceButton.addActionListener(this);

        // FIXME:  trackPool.getDisplaySequence().addPainter( this );
        panelLeft.add(resolutionTextArea);
        resolutionTextArea.setBorder(new TitledBorder("Square side for 1 arrow in pixels"));
        panelLeft.add(rayonRechercheTextArea);
        rayonRechercheTextArea.setBorder(new TitledBorder("Gaussian Parameter - Search Radius"));
        panelLeft.add(affectSequenceButton);
        panelLeft.add(removeZeroSpeedCheckBox);
        panelLeft.add(refreshComputeButton);
        refreshComputeButton.addActionListener(this);
        affectSequenceButton.addActionListener(this);
        removeZeroSpeedCheckBox.setSelected(true);
        removeZeroSpeedCheckBox.addActionListener(this);
        doNotReComputeCheckBox.setSelected(false);
        panelLeft.add(doNotReComputeCheckBox);
        panelLeft.add(displayGraphInSequenceCheckBox);
        panelLeft.add(useRoiAsBoundsForChartButton);

        //panelLeft.add( new JLabel("Scale:") );
        scaleTextField.setBorder(new TitledBorder("Graphic Scale on sequence"));

        panelLeft.add(scaleTextField);

        panel.add(panelLeft);
        panel.add(chartpanel);

        displayGraphInSequenceCheckBox.addActionListener(this);
        useRoiAsBoundsForChartButton.addActionListener(this);

        // FIXME: trackPool.getDisplaySequence().addListener( this );
    }

    JFreeChart chart;
    JPanel chartpanel = new JPanel();
    JCheckBox affectSequenceButton = new JCheckBox("Display on sequence.");
    final ArrayList<FlowArrow> flowArrowList = new ArrayList<>();
    JTextField resolutionTextArea = new JTextField("10");
    JTextField rayonRechercheTextArea = new JTextField("100");
    JButton refreshComputeButton = new JButton("Refresh.");
    JTextField scaleTextField = new JTextField("1.0");
    JCheckBox removeZeroSpeedCheckBox = new JCheckBox("Remove Zero-Speed Arrows.");
    JCheckBox doNotReComputeCheckBox = new JCheckBox("Do not automaticaly re-compute.");
    /**
     * If set to false, force recompute at Compute() call
     * Introduced to avoid computing at each compute() call
     */
    Boolean computed = false;
    JCheckBox displayGraphInSequenceCheckBox = new JCheckBox("Display graph on sequence.", false);
    JButton useRoiAsBoundsForChartButton = new JButton("place graph in ROI #1");

    int tmpT;

    @Override
    public void Compute() {
        synchronized (flowArrowList) {
            try {
                tmpT = trackPool.getTrackManager().getCurrentPositionT();
            }
            catch (final NullPointerException e) {
                tmpT = 0;
            }

            try {
                //trackPool.getDisplaySequence().addOverlay(this);
                trackPool.getDisplaySequence().addOverlay(overlay);
            }
            catch (final NullPointerException e) {
                // sequence or trackpool is null
            }

            if (!isEnabled()) return;

            if (doNotReComputeCheckBox.isSelected())
                if (computed) return;

            /* number of pixel for the square containing the arrow */
            double resolution;
            try {
                resolution = Double.parseDouble(resolutionTextArea.getText());
                if (resolution < 4) resolution = 4;
            }
            catch (final Exception e) {
                resolution = 10;
            }

            if (affectSequenceButton.isSelected()) {
                /* list which will contain the instant speed data */
                final ArrayList<InstantSpeed> instantSpeed = new ArrayList<>();

                // generates all instant speed;
                for (final TrackSegment ts : trackPool.getTrackSegmentList()) {

                    for (int i = 0; i < ts.getDetectionList().size() - 1; i++) {
                        final Detection d1 = ts.getDetectionList().get(i);
                        final Detection d2 = ts.getDetectionList().get(i + 1);

                        if (d1.isEnabled() && d2.isEnabled()) {
                            instantSpeed.add(new InstantSpeed(
                                    (d1.getX() + d2.getX()) / 2d,
                                    (d1.getY() + d2.getY()) / 2d,
                                    getDistance(d1, d2),
                                    d2.getX() - d1.getX(),
                                    d2.getY() - d1.getY()
                            ));
                        }
                    }
                }

                // generates Arrows
                flowArrowList.clear();

                double rayonRecherche;
                try {
                    rayonRecherche = Double.parseDouble(rayonRechercheTextArea.getText());
                    if (rayonRecherche < 4) rayonRecherche = 4;
                }
                catch (final Exception e) {
                    rayonRecherche = 100;
                }
                final Sequence sequence = trackPool.getDisplaySequence();

                int sequenceWidth = 100;
                int sequenceHeight = 100;

                if (sequence != null) {
                    sequenceWidth = trackPool.getDisplaySequence().getWidth();
                    sequenceHeight = trackPool.getDisplaySequence().getHeight();
                }

                {
                    for (double x = resolution / 2d; x < sequenceWidth; x += resolution)
                        for (double y = resolution / 2d; y < sequenceHeight; y += resolution) {
                            final FlowArrow flowarrow = new FlowArrow(x, y);

                            double nbIs = 0;
                            double sommGaussianAttenuation = 0;
                            for (final InstantSpeed is : instantSpeed) {
                                final double dis = getDistance(x, y, 0, is.x, is.y, 0);
                                {
                                    final double gaussianAttenuation = Math.exp(-(dis * dis) / (2d * rayonRecherche * rayonRecherche)) / Math.sqrt(2d * Math.PI * rayonRecherche * rayonRecherche);
                                    if (gaussianAttenuation > 0.00001) {
                                        flowarrow.vx += is.vx * gaussianAttenuation;
                                        flowarrow.vy += is.vy * gaussianAttenuation;
                                        nbIs++;
                                        sommGaussianAttenuation += gaussianAttenuation;
                                    }
                                }
                            }
                            if (nbIs > 0) {
                                //					flowarrow.vx /=sommGaussianAttenuation/= nbIs;
                                //					flowarrow.vy /=sommGaussianAttenuation/= nbIs;
                                flowarrow.vx /= nbIs;
                                flowarrow.vy /= nbIs;

                            }
                            flowArrowList.add(flowarrow);
                        }
                }

                for (final FlowArrow flowarrow : flowArrowList) {
                    flowarrow.norme = getDistance(0, 0, 0, flowarrow.vx, flowarrow.vy, 0);
                    flowarrow.angle = Math.atan2(flowarrow.vy, flowarrow.vx);
                }

                // normalize
                {
                    double max = 0;
                    for (final FlowArrow flowarrow : flowArrowList) {
                        if (flowarrow.norme > max) max = flowarrow.norme;
                    }

                    if (max > 0)
                        for (final FlowArrow flowarrow : flowArrowList) {
                            final double rapport = resolution / max;
                            flowarrow.norme *= rapport;
                            flowarrow.vx *= rapport;
                            flowarrow.vy *= rapport;

                            final float norme1 = (float) flowarrow.norme / (float) resolution;
                            //					flowarrow.color = new Color( norme1 , 0f , 1f - norme1 ) ;
                            flowarrow.color = new Color(norme1, 1f, 0f);
                        }
                }

            }


            // Generates graph.


            final XYSeriesCollection xyDataset = new XYSeriesCollection();
            final XYSeries series = new XYSeries("All Instant orientation");

            for (final FlowArrow flowarrow : flowArrowList) {
                series.add(Math.toDegrees(flowarrow.angle) + 90, flowarrow.norme);
            }

            xyDataset.addSeries(series);

            chart = ChartFactory.createPolarChart(
                    "Polar Inst. Speed",
                    xyDataset,
                    false, true, false);

            chartpanel.removeAll();
            chartpanel.add(new ChartPanel(chart, 180, 180, 180, 180, 180, 180, false, false, true, true, true, true));
            panel.updateUI();

            computed = true;
        }
    }

    static class FlowArrow implements Comparable<FlowArrow> {
        public FlowArrow(final double x, final double y) {
            this.x = x;
            this.y = y;
        }

        double norme;
        double vx = 0;
        double vy = 0;
        double x, y, angle, thickness;
        Color color;

        @Override
        public int compareTo(final FlowArrow o) {
            if (o.angle < this.angle) return -1;
            else if (o.angle > this.angle) return 1;
            return 0;
            //return 1;
        }
    }

    //TODO: comment x? y?
    static class InstantSpeed {
        public InstantSpeed(final double x, final double y, final double instantSpeed, final double vx, final double vy) {
            this.x = x;
            this.y = y;
            this.instantSpeed = instantSpeed;
            this.vx = vx;
            this.vy = vy;
        }

        double x;
        double y;
        double instantSpeed;
        double vx;
        double vy;

    }

    JLabel outLabel = new JLabel();

    public double getDistance(final double x1, final double y1, final double z1, final double x2, final double y2, final double z2) {
        return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2) + (z1 - z2) * (z1 - z2));
    }

    public double getDistance(final Detection d1, final Detection d2) {
        return getDistance(d1.getX(), d1.getY(), d1.getZ(), d2.getX(), d2.getY(), d2.getZ());
    }

    @Override
    public void actionPerformed(final ActionEvent e) {
        if (e.getSource() == useRoiAsBoundsForChartButton) {
            try {
                final Shape shape = (Shape) trackPool.getDisplaySequence().getROIs().getFirst();
                chartRectangleInSequence = (Rectangle2D) shape.getBounds2D().clone();
                displayGraphInSequenceCheckBox.setEnabled(true);
            }
            catch (final Exception e1) {
                MessageDialog.showDialog("No Sequence or No ROI to perform the operation.", MessageDialog.ERROR_MESSAGE);
            }
        }

        computed = false;
        trackPool.fireTrackEditorProcessorChange();
    }


    // ==============================
    // 		  PAINTER SUB PART
    // ==============================

//	public void keyPressed(Point p, KeyEvent e) {
//		
//	}
//
//	public void mouseClick(Point p, MouseEvent e) {
//
//	}
//
//	public void mouseDrag(Point p, MouseEvent e) {
//
//	}
//
//	public void mouseMove(Point p, MouseEvent e) {
//	}

//	public void paint(Graphics g) {
//		
//	}

    Rectangle2D chartRectangleInSequence = new Rectangle2D.Float(250, 20 + 260 * 0, 490, 240);

//	public void paint(Graphics3D g) {
//		
//	}

    @Override
    public void Close() {
        try {
            //trackPool.getDisplaySequence().removeOverlay(this);
            trackPool.getDisplaySequence().removeOverlay(overlay);
        }
        catch (final NullPointerException e) {
            // no sequence.
        }
    }


//	public void ROIChanged(SequenceChangeEvent e) {
//	}
//
//	public void dimensionChanged(SequenceChangeEvent e) {
//	}
//
//	public void nameChanged(SequenceChangeEvent e) {		
//	}

//	public void selectionChanged(SequenceChangeEvent e) {
//		trackPool.fireTrackEditorProcessorChange();		
//	}


    @Override
    public void viewerChanged(final ViewerEvent event) {
        if (event.getType() == ViewerEvent.ViewerEventType.POSITION_CHANGED) {
            trackPool.fireTrackEditorProcessorChange();
        }
    }

    @Override
    public void sequenceChanged(final SequenceEvent sequenceEvent) {
        for (final Viewer viewer : Icy.getMainInterface().getViewers()) {
            viewer.removeListener(this);
        }
        if (trackPool.getDisplaySequence() != null) {
            trackPool.getDisplaySequence().getViewers().getFirst().addListener(this);
        }
    }

    @Override
    public void sequenceClosed(final Sequence sequence) {

    }

    @Override
    public void displaySequenceChanged() {
        //for (final Sequence sequence : Icy.getMainInterface().getSequencesContaining(this)) {
        for (final Sequence sequence : Icy.getMainInterface().getSequencesContaining(overlay)) {
            //sequence.removeOverlay(this);
            sequence.removeOverlay(overlay);
        }

        final Sequence displaySequence = trackPool.getDisplaySequence();
        if (displaySequence != null) {
            //trackPool.getDisplaySequence().addOverlay(this);
            trackPool.getDisplaySequence().addOverlay(overlay);
        }
    }

    @Override
    public void viewerClosed(final Viewer viewer) {

    }

    private class TrackProcessorFlowOverlay extends Overlay {
        public TrackProcessorFlowOverlay() {
            super("TrackProcessorFlowOverlay");
        }

        @Override
        public void paint(final Graphics2D g, final Sequence sequence, final IcyCanvas canvas) {
            synchronized (flowArrowList) {
                double resolution;
                try {
                    resolution = Double.parseDouble(resolutionTextArea.getText());
                }
                catch (final Exception e) {
                    resolution = 1;
                }
                final float maxWidth = 4;
                final float minWidth = 1;


                if (!isEnabled()) return;
                if (!affectSequenceButton.isSelected()) return;

                g.setColor(Color.yellow);

                g.setStroke(new BasicStroke(2));
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                for (final FlowArrow flowarrow : flowArrowList) {
                    boolean display = true;
                    if (removeZeroSpeedCheckBox.isSelected()) {
                        if (flowarrow.norme < 0.1) {
                            display = false;
                        }
                    }

                    if (display) {
                        g.setStroke(new BasicStroke((float) (minWidth + (maxWidth - minWidth) * flowarrow.norme / resolution)));

                        g.setColor(flowarrow.color);
                        final AffineTransform at = g.getTransform();
                        g.translate((int) flowarrow.x, (int) flowarrow.y);
                        g.rotate(flowarrow.angle);
                        g.translate((int) -flowarrow.x, (int) -flowarrow.y);

                        final Line2D l1 = new Line2D.Double(
                                flowarrow.x - flowarrow.norme / 2,
                                flowarrow.y,
                                flowarrow.x - flowarrow.norme / 2 + flowarrow.norme,
                                flowarrow.y
                        );

                        final Line2D l2 = new Line2D.Double(
                                flowarrow.x - flowarrow.norme / 2 + 3 * flowarrow.norme / 4,
                                flowarrow.y + flowarrow.norme / 4,
                                flowarrow.x - flowarrow.norme / 2 + flowarrow.norme,
                                flowarrow.y
                        );

                        final Line2D l3 = new Line2D.Double(
                                flowarrow.x - flowarrow.norme / 2 + 3 * flowarrow.norme / 4,
                                flowarrow.y - flowarrow.norme / 4,
                                flowarrow.x - flowarrow.norme / 2 + flowarrow.norme,
                                flowarrow.y
                        );

                        g.draw(l1);
                        g.draw(l2);
                        g.draw(l3);
                        g.setTransform(at);
                    }
                }
                g.setStroke(new BasicStroke(1));

                // graphic

                double scale = Double.parseDouble(scaleTextField.getText());
                if (scale < 0.1) {
                    scale = 0.1;
                }
                final double minX = chartRectangleInSequence.getCenterX();
                final double minY = chartRectangleInSequence.getCenterY();

                final Rectangle2D transformedChartRectangleInSequence = (Rectangle2D) chartRectangleInSequence.clone();
                transformedChartRectangleInSequence.setRect(
                        (-chartRectangleInSequence.getWidth() / 2) * (1d / scale),
                        (-chartRectangleInSequence.getHeight() / 2) * (1d / scale),
                        chartRectangleInSequence.getWidth() * (1d / scale),
                        chartRectangleInSequence.getHeight() * (1d / scale));

                //Graphics2D g2 = (Graphics2D) g;

                final AffineTransform transform = g.getTransform();

                g.scale(scale, scale);
                g.translate(minX * (1d / scale), minY * (1d / scale));


                if (displayGraphInSequenceCheckBox.isSelected())
                    chart.draw(g, transformedChartRectangleInSequence);

                g.setTransform(transform);

                //		if ( displayGraphInSequenceCheckBox.isSelected() )
                //			chart.draw( (Graphics2D)g , chartRectangleInSequence );
            }
        }
    }
}
