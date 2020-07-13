/*
 * Copyright (c) 2014 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.labkey.variantdb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.action.SpringActionController;

public class VariantDBController extends SpringActionController
{
    private static final DefaultActionResolver _actionResolver = new DefaultActionResolver(VariantDBController.class);
    public static final String NAME = "variantdb";
    private static final Logger _log = LogManager.getLogger(VariantDBController.class);

    public VariantDBController()
    {
        setActionResolver(_actionResolver);
    }
}