'''Utilities relating to interaction with Marathon'''

import shakedown

import sdk_cmd
import sdk_utils


def get_config(app_name):
    # Be permissive of flakes when fetching the app content:
    def fn():
        return sdk_cmd.request('get', api_url('apps/{}'.format(app_name)), retry=False, log_args=False)
    config = shakedown.wait_for(lambda: fn()).json()['app']

    # The configuration JSON that marathon returns doesn't match the configuration JSON it accepts,
    # so we have to remove some offending fields to make it re-submittable, since it's not possible to
    # submit a partial config with only the desired fields changed.
    if 'uris' in config:
        del config['uris']

    if 'version' in config:
        del config['version']

    return config


def update_app(app_name, config):
    if "env" in config:
        sdk_utils.out("Environment for marathon app {} ({} values):".format(app_name, len(config["env"])))
        for k in sorted(config["env"]):
            sdk_utils.out("  {}={}".format(k, config["env"][k]))
    response = sdk_cmd.request('put', api_url('apps/{}'.format(app_name)), log_args=False, json=config)

    assert response.ok, "Marathon configuration update failed for {} with config {}".format(app_name, config)

    sdk_utils.out("Waiting for Marathon deployment of {} to complete...".format(app_name))
    shakedown.deployment_wait(app_id=app_name, timeout=600)


def destroy_app(app_name):
    shakedown.delete_app_wait(app_name)


def api_url(basename):
    return '{}/v2/{}'.format(shakedown.dcos_service_url('marathon'), basename)


def api_url_with_param(basename, path_param):
    return '{}/{}'.format(api_url(basename), path_param)


def get_scheduler_host(package_name):
    return shakedown.get_service_ips('marathon', package_name).pop()


def bump_cpu_count_config(package_name, key_name, delta=0.1):
    config = get_config(package_name)
    updated_cpus = float(config['env'][key_name]) + delta
    config['env'][key_name] = str(updated_cpus)
    update_app(package_name, config)
    return updated_cpus


def bump_task_count_config(package_name, key_name, delta=1):
    config = get_config(package_name)
    updated_node_count = int(config['env'][key_name]) + delta
    config['env'][key_name] = str(updated_node_count)
    update_app(package_name, config)
