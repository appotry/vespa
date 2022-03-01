// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa deploy command
// Author: bratseth

package cmd

import (
	"fmt"
	"log"

	"github.com/spf13/cobra"
	"github.com/vespa-engine/vespa/client/go/util"
	"github.com/vespa-engine/vespa/client/go/vespa"
)

const (
	zoneFlag     = "zone"
	logLevelFlag = "log-level"
)

var (
	zoneArg     string
	logLevelArg string
)

func init() {
	rootCmd.AddCommand(deployCmd)
	rootCmd.AddCommand(prepareCmd)
	rootCmd.AddCommand(activateCmd)
	deployCmd.PersistentFlags().StringVarP(&zoneArg, zoneFlag, "z", "", "The zone to use for deployment. This defaults to a dev zone")
	deployCmd.PersistentFlags().StringVarP(&logLevelArg, logLevelFlag, "l", "error", `Log level for Vespa logs. Must be "error", "warning", "info" or "debug"`)
}

var deployCmd = &cobra.Command{
	Use:   "deploy [application-directory]",
	Short: "Deploy (prepare and activate) an application package",
	Long: `Deploy (prepare and activate) an application package.

When this returns successfully the application package has been validated
and activated on config servers. The process of applying it on individual nodes
has started but may not have completed.

If application directory is not specified, it defaults to working directory.

When deploying to Vespa Cloud the system can be overridden by setting the
environment variable VESPA_CLI_CLOUD_SYSTEM. This is intended for internal use
only.`,
	Example: `$ vespa deploy .
$ vespa deploy -t cloud
$ vespa deploy -t cloud -z dev.aws-us-east-1c  # -z can be omitted here as this zone is the default
$ vespa deploy -t cloud -z perf.aws-us-east-1c`,
	Args:              cobra.MaximumNArgs(1),
	DisableAutoGenTag: true,
	SilenceUsage:      true,
	RunE: func(cmd *cobra.Command, args []string) error {
		pkg, err := vespa.FindApplicationPackage(applicationSource(args), true)
		if err != nil {
			return err
		}
		cfg, err := LoadConfig()
		if err != nil {
			return err
		}
		target, err := getTarget()
		if err != nil {
			return err
		}
		opts, err := getDeploymentOptions(cfg, pkg, target)
		if err != nil {
			return err
		}

		var sessionOrRunID int64
		err = util.Spinner(stderr, "Uploading application package ...", func() error {
			sessionOrRunID, err = vespa.Deploy(opts)
			return err
		})
		if err != nil {
			return err
		}

		log.Println()
		if opts.IsCloud() {
			printSuccess("Triggered deployment of ", color.Cyan(pkg.Path), " with run ID ", color.Cyan(sessionOrRunID))
		} else {
			printSuccess("Deployed ", color.Cyan(pkg.Path))
		}
		if opts.IsCloud() {
			log.Printf("\nUse %s for deployment status, or follow this deployment at", color.Cyan("vespa status"))
			log.Print(color.Cyan(fmt.Sprintf("%s/tenant/%s/application/%s/dev/instance/%s/job/%s-%s/run/%d",
				opts.Target.Deployment().System.ConsoleURL,
				opts.Target.Deployment().Application.Tenant, opts.Target.Deployment().Application.Application, opts.Target.Deployment().Application.Instance,
				opts.Target.Deployment().Zone.Environment, opts.Target.Deployment().Zone.Region,
				sessionOrRunID)))
		}
		return waitForQueryService(sessionOrRunID)
	},
}

var prepareCmd = &cobra.Command{
	Use:               "prepare application-directory",
	Short:             "Prepare an application package for activation",
	Args:              cobra.MaximumNArgs(1),
	DisableAutoGenTag: true,
	SilenceUsage:      true,
	RunE: func(cmd *cobra.Command, args []string) error {
		pkg, err := vespa.FindApplicationPackage(applicationSource(args), true)
		if err != nil {
			return fmt.Errorf("could not find application package: %w", err)
		}
		cfg, err := LoadConfig()
		if err != nil {
			return err
		}
		target, err := getTarget()
		if err != nil {
			return err
		}
		var sessionID int64
		err = util.Spinner(stderr, "Uploading application package ...", func() error {
			sessionID, err = vespa.Prepare(vespa.DeploymentOptions{
				ApplicationPackage: pkg,
				Target:             target,
			})
			return err
		})
		if err != nil {
			return err
		}
		if err := cfg.WriteSessionID(vespa.DefaultApplication, sessionID); err != nil {
			return fmt.Errorf("could not write session id: %w", err)
		}
		printSuccess("Prepared ", color.Cyan(pkg.Path), " with session ", sessionID)
		return nil
	},
}

var activateCmd = &cobra.Command{
	Use:               "activate",
	Short:             "Activate (deploy) a previously prepared application package",
	Args:              cobra.MaximumNArgs(1),
	DisableAutoGenTag: true,
	SilenceUsage:      true,
	RunE: func(cmd *cobra.Command, args []string) error {
		pkg, err := vespa.FindApplicationPackage(applicationSource(args), true)
		if err != nil {
			return fmt.Errorf("could not find application package: %w", err)
		}
		cfg, err := LoadConfig()
		if err != nil {
			return err
		}
		sessionID, err := cfg.ReadSessionID(vespa.DefaultApplication)
		if err != nil {
			return fmt.Errorf("could not read session id: %w", err)
		}
		target, err := getTarget()
		if err != nil {
			return err
		}
		err = vespa.Activate(sessionID, vespa.DeploymentOptions{
			ApplicationPackage: pkg,
			Target:             target,
		})
		if err != nil {
			return err
		}
		printSuccess("Activated ", color.Cyan(pkg.Path), " with session ", sessionID)
		return waitForQueryService(sessionID)
	},
}

func waitForQueryService(sessionOrRunID int64) error {
	if waitSecsArg > 0 {
		log.Println()
		return waitForService("query", sessionOrRunID)
	}
	return nil
}
