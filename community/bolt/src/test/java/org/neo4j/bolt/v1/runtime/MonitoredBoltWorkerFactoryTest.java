/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.v1.runtime;

import org.junit.Test;
import org.neo4j.bolt.testing.NullResponseHandler;
import org.neo4j.bolt.v1.runtime.MonitoredWorkerFactory.MonitoredBoltWorker;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.time.FakeClock;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MonitoredBoltWorkerFactoryTest
{
    @Test
    public void shouldSignalReceivedStartAndComplete() throws Throwable
    {
        // given
        FakeClock clock = new FakeClock();

        WorkerFactory delegate = mock( WorkerFactory.class );
        BoltStateMachine machine = mock( BoltStateMachine.class );
        when( delegate.newWorker( anyString(), anyObject() ) )
                .thenReturn( new BoltWorker()
                {
                    @Override
                    public void enqueue( Job job )
                    {
                        clock.forward( 1337, TimeUnit.MILLISECONDS );
                        try
                        {
                            job.perform( machine );
                        }
                        catch ( BoltConnectionFatality connectionFatality )
                        {
                            throw new RuntimeException( connectionFatality );
                        }
                    }

                    @Override
                    public void interrupt()
                    {
                        throw new RuntimeException();
                    }

                    @Override
                    public void halt()
                    {
                        throw new RuntimeException();
                    }

                } );

        Monitors monitors = new Monitors();
        CountingSessionMonitor monitor = new CountingSessionMonitor();
        monitors.addMonitorListener( monitor );

        MonitoredWorkerFactory workerFactory = new MonitoredWorkerFactory( monitors, delegate, clock );
        BoltWorker worker = workerFactory.newWorker( "<test>" );

        // when
        worker.enqueue( ( stateMachine ) -> {
            stateMachine.run( "hello", null, new NullResponseHandler() );
            clock.forward( 1338, TimeUnit.MILLISECONDS );
        } );

        // then
        assertEquals( 1, monitor.messagesReceived );
        assertEquals( 1337, monitor.queueTime );
        assertEquals( 1338, monitor.processingTime );
    }

    @Test
    public void shouldNotWrapWithMonitoredSessionIfNobodyIsListening() throws Throwable
    {
        // Given
        // Monitoring adds GC overhead, so we only want to do the work involved
        // if someone has actually registered a listener. We still allow plugging
        // monitoring in at runtime, but it will only apply to sessions started
        // after monitor listeners are added
        WorkerFactory workerFactory = mock( WorkerFactory.class );
        BoltWorker innerSession = mock( BoltWorker.class );
        when( workerFactory.newWorker( anyString(), anyObject() ) )
                .thenReturn( innerSession );

        Monitors monitors = new Monitors();
        MonitoredWorkerFactory monitoredWorkerFactory = new MonitoredWorkerFactory( monitors, workerFactory, new FakeClock() );

        // When
        BoltWorker worker = monitoredWorkerFactory.newWorker( "<test>" );

        // Then
        assertEquals( innerSession, worker );

        // But when I register a listener
        monitors.addMonitorListener( new CountingSessionMonitor() );

        // Then new sessions should be monitored
        assertThat( monitoredWorkerFactory.newWorker( "<test>" ), instanceOf( MonitoredBoltWorker.class ) );
    }

    private static class CountingSessionMonitor implements MonitoredWorkerFactory.SessionMonitor
    {
       long messagesReceived = 0;
       long queueTime = 0;
       long processingTime = 0;

        @Override
        public void messageReceived()
        {
            messagesReceived++;
        }

        @Override
        public void processingStarted( long queueTime )
        {
            this.queueTime += queueTime;
        }

        @Override
        public void processingDone( long processingTime )
        {
            this.processingTime += processingTime;
        }
    }
}
