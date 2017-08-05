/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.ui.externallinks.internal;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bundle activator for external dashboard tiles.
 *
 * @author Pauli Anttila - Initial Contribution
 *
 */
public class Activator implements BundleActivator {

    private final Logger logger = LoggerFactory.getLogger(Activator.class);

    private static BundleContext context;

    private List<ServiceRegistration<?>> dashboardTileRegistrations = new ArrayList<ServiceRegistration<?>>();;

    /**
     * Called whenever the OSGi framework starts our bundle
     */
    @Override
    public void start(BundleContext bc) throws Exception {
        context = bc;

        final String FILENAME = "links.txt";

        String current = new java.io.File(".").getCanonicalPath();
        logger.info("Loading dashboard links from file '{}'", current + '/' + FILENAME);

        BufferedReader br = null;

        try {

            br = new BufferedReader(new FileReader(FILENAME));

            String line;

            while ((line = br.readLine()) != null) {

                JSONObject obj = new JSONObject(line);

                String name = obj.getString("name");
                String url = obj.getString("url");
                String overlay = obj.getString("overlay");
                String imageUrl = obj.getString("imageUrl");

                logger.info("Registering dashboard link '{}', url='{}', overlay='{}', imageUrl='{}'", name, url,
                        overlay, imageUrl);
                ServiceRegistration<?> a = bc.registerService(org.openhab.ui.dashboard.DashboardTile.class.getName(),
                        new DashboardTileTemplate.DashboardTileBuilder().withName(name).withUrl(url)
                                .withOverlay(overlay).withImageUrl(imageUrl).build(),
                        null);

                dashboardTileRegistrations.add(a);
            }
        } catch (IOException e) {
            logger.error("Error occured when loading links file", e);

        } catch (JSONException e) {
            logger.error("Error occured when loading links file", e);

        } finally {

            try {

                if (br != null) {
                    br.close();
                }

            } catch (IOException ex) {
                logger.error("Error occured when closing links file", ex);
            }
        }

        logger.debug("Dashboard Tiles Registered.");

    }

    /**
     * Called whenever the OSGi framework stops our bundle
     */
    @Override
    public void stop(BundleContext bc) throws Exception {
        context = null;

        if (dashboardTileRegistrations != null) {
            dashboardTileRegistrations.forEach((serv) -> {
                serv.unregister();
            });
            logger.debug("Dashboard Tiles unregistered.");
        }
    }

    /**
     * Returns the bundle context of this bundle
     *
     * @return the bundle context
     */
    public static BundleContext getContext() {
        return context;
    }
}
