/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.dispatch.internal;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.dispatch.DispatchObject;
import org.apache.activemq.dispatch.DispatchQueue;

/**
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
abstract public class AbstractDispatchObject extends BaseRetained implements DispatchObject {

    protected volatile Object context;
    protected volatile DispatchQueue targetQueue;
    protected final AtomicInteger suspendCounter = new AtomicInteger();

    @SuppressWarnings("unchecked")
    public <Context> Context getContext() {
        return (Context) context;
    }
    
    public <Context> void setContext(Context context) {
        this.context = context;
    }

    public void setTargetQueue(DispatchQueue targetQueue) {
        this.targetQueue = targetQueue;
    }

    public DispatchQueue getTargetQueue() {
        return this.targetQueue;
    }
    
    public void resume() {
        if( suspendCounter.decrementAndGet() == 0 ) {
            onResume();
        }
    }

    public void suspend() {
        suspendCounter.incrementAndGet();
    }
    
    protected void onResume() {
    }

}