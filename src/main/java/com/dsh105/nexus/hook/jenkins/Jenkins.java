/*
 * This file is part of Nexus.
 *
 * Nexus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Nexus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Nexus.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.dsh105.nexus.hook.jenkins;

import com.dsh105.nexus.Nexus;
import com.dsh105.nexus.exception.jenkins.JenkinsJobException;
import com.dsh105.nexus.exception.jenkins.JenkinsJobNotFoundException;
import com.dsh105.nexus.util.JsonUtil;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import java.io.FileNotFoundException;
import java.util.*;

public class Jenkins {

    protected String JENKINS_URL;
    private HashSet<JenkinsJob> jobs = new HashSet<>();
    private HashMap<String, JenkinsJobEntry> jobEntries = new HashMap<>();
    public final RefreshTask TASK;

    public Jenkins(String jenkinsBaseUrl) {
        this.JENKINS_URL = jenkinsBaseUrl;
        TASK = new RefreshTask();
        new Timer(true).schedule(TASK, 0, 150000);
    }

    public static boolean testConnection() {
        return testConnection(Nexus.getInstance().getConfig().getJenkinsUrl());
    }

    public static boolean testConnection(String jenkinsUrl) {
        try {
            Unirest.get(jenkinsUrl + "api/json").asJson();
            return true;
        } catch (UnirestException e) {
            return false;
        }
    }

    public static Jenkins getJenkins() {
        return Nexus.getInstance().getJenkins();
    }

    public void requestBuild(String jobName) {
        Nexus.LOGGER.info("Requesting build initiation of Jenkins job (" + jobName + ") at " + JENKINS_URL);
        JenkinsJob job = getJob(jobName);
        if (job == null) {
            throw new JenkinsJobNotFoundException("Failed to locate Jenkins API!");
        }
        String token = Nexus.getInstance().getConfig().get("jenkins-token-" + job.getJobName(), "");
        if (!JENKINS_URL.isEmpty() && !token.isEmpty()) {
            try {
                Unirest.get(JENKINS_URL + "job/" + job.getJobName() + "/build?token=" + token).asString();
            } catch (UnirestException e) {
                if (e.getCause() instanceof FileNotFoundException) {
                    throw new JenkinsJobNotFoundException("Failed to locate Jenkins job: " + job.getJobName(), e);
                }
                throw new JenkinsJobException("Failed to connect to Jenkins API!", e);
            }
        }
    }

    public JenkinsJobEntry getJobEntry(String jobName) {
        JenkinsJob job = this.getJob(jobName);
        return job == null ? null : job.getJobEntry();
    }

    public JenkinsJob getJob(String jobName) {
        Nexus.LOGGER.info("Requesting Jenkins job (" + jobName + ") at " + JENKINS_URL);
        if (!this.jobs.isEmpty()) {
            for (JenkinsJob job : getJobs()) {
                if (job.getJobName().equalsIgnoreCase(jobName)) {
                    return job;
                }
            }
        }

        // Couldn't find it using the above method, so let's try connecting to it directly
        try {
            JenkinsJobEntry jobEntry = JsonUtil.read(Unirest.get(JENKINS_URL + "job/" + jobName + "/api/json"), JenkinsJobEntry.class);
            return new JenkinsJob(jobEntry.getName(), jobEntry);
        } catch (UnirestException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                throw new JenkinsJobNotFoundException("Failed to locate Jenkins API!", e);
            }
            throw new JenkinsJobException("Failed to connect to Jenkins API!", e);
        }
    }

    public Set<JenkinsJob> getJobs() {
        return getJobs(false);
    }

    public Set<JenkinsJob> getJobs(boolean reconnect) {
        if (reconnect || this.jobEntries.isEmpty()) {
            JenkinsJobEntry[] jobs;
            try {
                jobs = JsonUtil.read(Unirest.get(JENKINS_URL + "api/json"), "jobs", JenkinsJobEntry[].class);
            } catch (UnirestException e) {
                throw new JenkinsJobException("Failed to connect to Jenkins API!", e);
            }
            if (jobs != null) {
                this.updateJobs(jobs);
            }
        }
        return new HashSet<>(this.jobs);
    }

    private void updateJobs(JenkinsJobEntry[] jobs) {
        if (jobs.length > 0) {
            this.jobEntries.clear();
            this.jobs.clear();
            for (JenkinsJobEntry entry : jobs) {
                this.jobEntries.put(entry.getName(), entry);
                this.jobs.add(new JenkinsJob(entry.getName(), entry));
            }
        }
    }


    public class RefreshTask extends TimerTask {

        @Override
        public void run() {
            //Nexus.LOGGER.info("Updating all jenkins jobs at " + JENKINS_URL);
            getJobs(true);
        }
    }
}
